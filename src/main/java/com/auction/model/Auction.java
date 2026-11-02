package com.auction.model;

import com.auction.exception.AuctionClosedException;
import com.auction.exception.AuctionException;
import com.auction.exception.AuctionFinishedException;
import com.auction.exception.InsufficientBalanceException;
import com.auction.exception.InvalidBidException;
import com.auction.utils.AuctionObserver;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Auction extends Entity implements Serializable {
    private static final long serialVersionUID = 1L;

    // --- CÁC HẰNG SỐ CHO ANTI-SNIPING ---
    private static final int SNIPE_THRESHOLD_SECONDS = 30;
    private static final int EXTENSION_SECONDS = 60;

    // Dùng chung Thread Pool (Daemon) cho toàn hệ thống để chống rò rỉ luồng (Thread Leak)
    private static final ExecutorService SHARED_NOTIFICATION_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Auction-Notifier");
        t.setDaemon(true);
        return t;
    });

    private static final ScheduledExecutorService SHARED_SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "Auction-AutoCloser");
        t.setDaemon(true);
        return t;
    });

    private final List<AutoBidRule> autoBidRules;
    private final List<BidTransaction> bidHistory;
    private final List<AuctionObserver> observers;

    private Item item;
    private Seller seller;
    private double currentHighestBid;
    private Bidder highestBidder;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private AuctionStatus status;
    private boolean cancelRefunded = false;

    // Task hẹn giờ tự động đóng phiên
    private transient ScheduledFuture<?> autoCloseTask;

    public Auction(Item item, Seller seller, LocalDateTime startTime, LocalDateTime endTime) {
        super();
        this.item = Objects.requireNonNull(item, "Item không được null");
        this.seller = Objects.requireNonNull(seller, "Seller không được null");
        this.startTime = Objects.requireNonNull(startTime, "StartTime không được null");
        this.endTime = Objects.requireNonNull(endTime, "EndTime không được null");

        this.currentHighestBid = item.getStartingPrice();
        this.status = AuctionStatus.PENDING_APPROVAL;
        this.bidHistory = new CopyOnWriteArrayList<>();
        this.autoBidRules = new CopyOnWriteArrayList<>();
        this.observers = new CopyOnWriteArrayList<>();
    }

    public synchronized void processBid(BidTransaction transaction) throws AuctionException {
        Objects.requireNonNull(transaction, "Giao dịch đặt giá không được null");
        Bidder newBidder = transaction.getBidder();

        // 1. Kiểm tra tính hợp lệ của lượt đặt giá
        validateBid(transaction);

        // 2. Kích hoạt anti-sniping (Gia hạn thời gian & đặt lại lịch auto-close nếu ở phút chót)
        applyAntiSniping();

        // 3. Hoàn tiền tạm giữ cho người dẫn đầu cũ (Khóa đồng bộ trên đối tượng Bidder để an toàn đa luồng)
        if (this.highestBidder != null) {
            double refund = roundCurrency(this.currentHighestBid);
            synchronized (this.highestBidder) {
                this.highestBidder.setBalance(roundCurrency(this.highestBidder.getBalance() + refund));
            }
            if (!this.highestBidder.getId().equals(newBidder.getId())) {
                System.out.printf("↩️  [REFUND] Hoàn %.2f cho %s (bị vượt bởi %s)%n",
                        refund, this.highestBidder.getUserName(), newBidder.getUserName());
            } else {
                System.out.printf("🔄 [SELF-RAISE] Hoàn %.2f cho %s (tự nâng từ %.2f → %.2f, chỉ trừ delta %.2f)%n",
                        refund, this.highestBidder.getUserName(),
                        this.currentHighestBid, transaction.getBidAmount(),
                        transaction.getBidAmount() - this.currentHighestBid);
            }
        }

        // 4. Cập nhật trạng thái phiên đấu giá
        this.currentHighestBid = roundCurrency(transaction.getBidAmount());
        this.highestBidder = newBidder;
        this.bidHistory.add(transaction);

        // 5. Thông báo bất đồng bộ tới các Observer
        notifyObservers(transaction);

        // 6. Kích hoạt hệ thống đấu giá tự động (Auto-Bid)
        triggerAutoBids();
    }

    private void validateBid(BidTransaction transaction) throws AuctionException {
        if (LocalDateTime.now().isAfter(this.endTime)) {
            this.status = AuctionStatus.FINISHED;
            throw new AuctionFinishedException("Phiên đấu giá đã kết thúc vào lúc " + this.endTime);
        }

        if (this.status != AuctionStatus.RUNNING
                && this.status != AuctionStatus.OPEN
                && this.status != AuctionStatus.APPROVED) {
            throw new AuctionClosedException("Phiên đấu giá không ở trạng thái hoạt động. Trạng thái: " + this.status);
        }

        double bidAmount = roundCurrency(transaction.getBidAmount());
        if (bidAmount <= 0) {
            throw new InvalidBidException("Giá thầu không hợp lệ. Vui lòng nhập số tiền lớn hơn 0.");
        }

        if (bidAmount <= this.currentHighestBid) {
            throw new InvalidBidException("Giá thầu phải cao hơn giá hiện tại.",
                    this.currentHighestBid, bidAmount);
        }

        Bidder bidder = transaction.getBidder();
        if (bidder.getId().equals(this.seller.getId())) {
            throw new InvalidBidException("Người bán không được phép tự đấu giá sản phẩm của mình!");
        }

        // Nếu cùng 1 người tự nâng giá (self-raise), số dư thực tế khả dụng bao gồm cả khoản tiền đang đặt cọc ở phiên này
        synchronized (bidder) {
            double effectiveBalance = bidder.getBalance();
            if (this.highestBidder != null && this.highestBidder.getId().equals(bidder.getId())) {
                effectiveBalance = roundCurrency(effectiveBalance + this.currentHighestBid);
            }
            if (effectiveBalance < bidAmount) {
                throw new InsufficientBalanceException("Số dư không đủ.", bidder.getBalance(), bidAmount);
            }
        }
    }

    private synchronized void applyAntiSniping() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thresholdTime = this.endTime.minusSeconds(SNIPE_THRESHOLD_SECONDS);

        if (now.isAfter(thresholdTime) && now.isBefore(this.endTime)) {
            this.endTime = this.endTime.plusSeconds(EXTENSION_SECONDS);
            // Quan trọng: Đặt lại lịch đóng phiên theo thời gian mới
            if (this.status == AuctionStatus.RUNNING) {
                scheduleAutoClose();
            }
            notifyObservers("🛡️ Có người đặt giá phút chót! Phiên đấu giá gia hạn thêm " + EXTENSION_SECONDS
                    + " giây. Kết thúc lúc: " + this.endTime);
        }
    }

    public synchronized void triggerAutoBidsPublic() {
        triggerAutoBids();
    }

    private void triggerAutoBids() {
        List<AutoBidRule> sortedRules = new ArrayList<>();
        for (AutoBidRule rule : this.autoBidRules) {
            if (rule.isActive()) {
                sortedRules.add(rule);
            }
        }

        sortedRules.sort((r1, r2) -> {
            int priceCompare = Double.compare(r2.getMaxBid(), r1.getMaxBid());
            if (priceCompare == 0) {
                return r1.getRegisterTime().compareTo(r2.getRegisterTime());
            }
            return priceCompare;
        });

        boolean hasNewAction;
        do {
            hasNewAction = false;

            for (AutoBidRule topRule : sortedRules) {
                if (!topRule.isActive() || (this.highestBidder != null
                        && topRule.getBidder().getId().equals(this.highestBidder.getId()))) {
                    continue;
                }

                double targetPrice = roundCurrency(this.currentHighestBid + topRule.getIncrement());

                if (targetPrice <= topRule.getMaxBid()) {
                    try {
                        Bidder autoBidder = topRule.getBidder();
                        BidTransaction autoTx = new BidTransaction(this, autoBidder, targetPrice);

                        Bidder previousHighest = this.highestBidder;
                        double previousBidAmount = roundCurrency(this.currentHighestBid);

                        validateBid(autoTx);

                        // 1. Hoàn tiền cho người dẫn đầu cũ
                        if (previousHighest != null && !previousHighest.getId().equals(autoBidder.getId())) {
                            synchronized (previousHighest) {
                                previousHighest.setBalance(roundCurrency(previousHighest.getBalance() + previousBidAmount));
                            }
                            System.out.printf("↩️  [AUTOBID REFUND] Hoàn %.2f cho %s%n",
                                    previousBidAmount, previousHighest.getUserName());
                        }

                        // 2. Trừ tiền của người đặt Auto-Bid mới
                        synchronized (autoBidder) {
                            autoBidder.setBalance(roundCurrency(autoBidder.getBalance() - targetPrice));
                        }

                        // 3. Cập nhật phiên đấu giá
                        this.currentHighestBid = targetPrice;
                        this.highestBidder = autoBidder;
                        this.bidHistory.add(autoTx);
                        autoBidder.addTransaction(autoTx);

                        System.out.printf("💸 [AUTOBID DEDUCT] Trừ %.2f từ %s (số dư còn: %.2f)%n",
                                targetPrice, autoBidder.getUserName(), autoBidder.getBalance());
                        System.out.println("🤖 [AUTO-BID] Tự động trả giá $" + targetPrice + " thay cho "
                                + autoBidder.getUserName());

                        notifyObservers(autoTx);
                        hasNewAction = true;
                        break;

                    } catch (AuctionException e) {
                        System.out.println("⚠️ [AUTO-BID TẮT] Hủy lệnh của " + topRule.getBidder().getUserName()
                                + " vì: " + e.getMessage());
                        topRule.setActive(false);
                    }
                } else {
                    System.out.println("🏳️ [AUTO-BID TẮT] " + topRule.getBidder().getUserName() + " đã chạm trần Max Bid.");
                    topRule.setActive(false);
                }
            }
        } while (hasNewAction);
    }

    public synchronized boolean updateAuctionDetails(User requestor, Item newItem, LocalDateTime newStart,
                                                     LocalDateTime newEnd) {
        if (this.status != AuctionStatus.OPEN && this.status != AuctionStatus.PENDING_APPROVAL) {
            return false;
        }

        boolean isOwner = requestor.getId().equals(this.seller.getId());
        boolean isAdmin = requestor instanceof Admin;

        if (isOwner || isAdmin) {
            this.item = newItem;
            this.startTime = newStart;
            this.endTime = newEnd;
            return true;
        }
        return false;
    }

    public synchronized boolean cancelAuction(User requestor, String reason) {
        if (this.status == AuctionStatus.FINISHED || this.status == AuctionStatus.PAID
                || this.status == AuctionStatus.CANCELED) {
            return false;
        }

        boolean isOwner = requestor.getId().equals(this.seller.getId());
        boolean isAdmin = requestor instanceof Admin;

        if (isOwner || isAdmin) {
            this.status = AuctionStatus.CANCELED;
            cancelAutoCloseTask();
            return true;
        }
        return false;
    }

    public synchronized void startAuction() {
        if (this.status == AuctionStatus.OPEN || this.status == AuctionStatus.APPROVED) {
            this.status = AuctionStatus.RUNNING;
            scheduleAutoClose();
        }
    }

    private synchronized void scheduleAutoClose() {
        try {
            // Hủy lịch đóng cũ nếu đang tồn tại (rất quan trọng khi Anti-Sniping gia hạn giờ)
            cancelAutoCloseTask();

            long secondsUntilEnd = ChronoUnit.SECONDS.between(LocalDateTime.now(), this.endTime);
            if (secondsUntilEnd <= 0) {
                secondsUntilEnd = 1;
            }

            autoCloseTask = SHARED_SCHEDULER.schedule(() -> {
                System.out.println("⏰ [AUTO-CLOSE] Phiên đấu giá \"" + this.item.getNameItem()
                        + "\" hết thời gian, tự động kết thúc!");
                closeAuction();
            }, secondsUntilEnd, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.err.println("❌ Lỗi khi lên lịch auto-close: " + e.getMessage());
        }
    }

    private void cancelAutoCloseTask() {
        if (this.autoCloseTask != null && !this.autoCloseTask.isDone()) {
            this.autoCloseTask.cancel(false);
            this.autoCloseTask = null;
        }
    }

    public synchronized void closeAuction() {
        if (this.status == AuctionStatus.RUNNING
                || this.status == AuctionStatus.OPEN
                || this.status == AuctionStatus.APPROVED) {
            this.status = AuctionStatus.FINISHED;
            cancelAutoCloseTask();
            determineWinner();
        }
    }

    public void determineWinner() {
        if (this.highestBidder != null) {
            System.out.println("Người chiến thắng: " + this.highestBidder.getUserName()
                    + " với mức giá: " + this.currentHighestBid);
        } else {
            System.out.println("Không có ai tham gia trả giá cho phiên đấu giá này.");
        }
    }

    public synchronized void settleAuction() {
        if (this.status == AuctionStatus.PAID) {
            System.out.println("ℹ️ Phiên đấu giá " + this.id + " đã được settlement rồi!");
            return;
        }

        if (this.status != AuctionStatus.FINISHED) {
            System.out.println("⚠️ Lỗi: Phiên đấu giá phải ở trạng thái FINISHED mới có thể settlement!");
            return;
        }

        if (this.highestBidder == null) {
            System.out.println("ℹ️ Lỗi: Phiên đấu giá không có người thắng cuộc!");
            return;
        }

        try {
            double bidAmount = roundCurrency(this.currentHighestBid);
            double newBalance;

            synchronized (this.seller) {
                newBalance = roundCurrency(this.seller.getBalance() + bidAmount);
                this.seller.setBalance(newBalance);

                AuctionEarning earning = new AuctionEarning(
                        this.getAuctionId(),
                        this.item.getNameItem(),
                        this.highestBidder.getUserName(),
                        bidAmount,
                        newBalance,
                        "PAID"
                );
                this.seller.addEarning(earning);
            }

            this.status = AuctionStatus.PAID;
            System.out.println("💰 [Settlement] Cộng $" + bidAmount + " vào tài khoản Seller "
                    + this.seller.getUserName() + " (Bidder " + this.highestBidder.getUserName()
                    + " đã bị trừ tiền khi đặt giá)");
            System.out.println("✅ [Settlement] Hoàn tất! Trạng thái: " + this.status);
        } catch (Exception e) {
            System.err.println("❌ [Settlement Error] " + e.getMessage());
        }
    }

    public synchronized void refundOnCancel() {
        if (this.cancelRefunded) {
            System.out.println("ℹ️ [CANCEL REFUND] Phiên đấu giá này đã được hoàn tiền trước đó.");
            return;
        }
        if (this.highestBidder == null) {
            System.out.println("ℹ️ [CANCEL REFUND] Không có bidder nào để hoàn tiền.");
            return;
        }

        double refundAmount = roundCurrency(this.currentHighestBid);
        double newBalance;
        synchronized (this.highestBidder) {
            newBalance = roundCurrency(this.highestBidder.getBalance() + refundAmount);
            this.highestBidder.setBalance(newBalance);
        }
        this.cancelRefunded = true;

        System.out.println("💸 [CANCEL REFUND] Hoàn " + refundAmount
                + " cho bidder " + this.highestBidder.getUserName()
                + " (Số dư mới: " + newBalance + ")");
    }

    public synchronized void registerAutoBid(Bidder bidder, double maxBid, double increment) throws AuctionException {
        if (this.status == AuctionStatus.FINISHED
                || this.status == AuctionStatus.CANCELED
                || this.status == AuctionStatus.PAID) {
            throw new AuctionClosedException("Lỗi: Không thể cài Auto-bid vì phiên đấu giá đã kết thúc!");
        }

        if (bidder.getId().equals(this.seller.getId())) {
            throw new InvalidBidException("Người bán không được thiết lập Auto-bid cho sản phẩm của mình!");
        }

        if (maxBid <= this.currentHighestBid) {
            throw new InvalidBidException("Giá tối đa (Max Bid) phải lớn hơn giá hiện tại!");
        }

        if (increment <= 0) {
            throw new InvalidBidException("Bước giá (Increment) phải là một số lớn hơn 0!");
        }

        synchronized (bidder) {
            double effectiveBalance = bidder.getBalance();
            if (this.highestBidder != null && this.highestBidder.getId().equals(bidder.getId())) {
                effectiveBalance = roundCurrency(effectiveBalance + this.currentHighestBid);
            }
            if (effectiveBalance < maxBid) {
                throw new InsufficientBalanceException("Số dư không đủ để thiết lập Auto-bid tới mức giá này.",
                        bidder.getBalance(), maxBid);
            }
        }

        // Vô hiệu hóa rule cũ của cùng Bidder (nếu có) trước khi thêm rule mới để tránh 1 người có 2 robot tự đấu nhau
        for (AutoBidRule existingRule : this.autoBidRules) {
            if (existingRule.isActive() && existingRule.getBidder().getId().equals(bidder.getId())) {
                existingRule.setActive(false);
            }
        }

        AutoBidRule newRule = new AutoBidRule(bidder, roundCurrency(maxBid), roundCurrency(increment));
        this.autoBidRules.add(newRule);
        System.out.println("✅ " + bidder.getUserName() + " đã cài Auto-bid (Max: " + maxBid + ", Bước giá: " + increment + ")");
    }

    // OBSERVER PATTERN
    public synchronized void addObserver(AuctionObserver observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public synchronized void removeObserver(AuctionObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(String message) {
        for (AuctionObserver obs : observers) {
            CompletableFuture.runAsync(() -> obs.update(message), SHARED_NOTIFICATION_POOL);
        }
    }

    private void notifyObservers(BidTransaction tx) {
        for (AuctionObserver obs : observers) {
            CompletableFuture.runAsync(() -> obs.onNewBidPlaced(tx), SHARED_NOTIFICATION_POOL);
        }
    }

    private static double roundCurrency(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        this.autoCloseTask = null;
    }

    // GETTERS & SETTERS
    public String getAuctionId() {
        return this.getId();
    }

    public Item getItem() {
        return item;
    }

    public Seller getSeller() {
        return seller;
    }

    public double getCurrentHighestBid() {
        return currentHighestBid;
    }

    public Bidder getHighestBidder() {
        return highestBidder;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public AuctionStatus getStatus() {
        return status;
    }

    public List<BidTransaction> getBidHistory() {
        return Collections.unmodifiableList(bidHistory);
    }

    public List<AutoBidRule> getAutoBidRules() {
        return Collections.unmodifiableList(autoBidRules);
    }

    public synchronized void setStatus(AuctionStatus status) {
        this.status = status;
    }

    public synchronized void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public synchronized void setSeller(Seller seller) {
        this.seller = seller;
    }
}