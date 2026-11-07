package com.auction.strategy;

import com.auction.exception.AuctionClosedException;
import com.auction.exception.AuctionException;
import com.auction.exception.AuctionFinishedException;
import com.auction.model.Auction;
import com.auction.model.AuctionStatus;
import com.auction.model.BidTransaction;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * AutoBidStrategy — Chiến lược đặt giá tự động (robot).
 *
 * Ủy quyền toàn bộ việc sắp xếp độ ưu tiên AutoBidRule, kiểm tra số dư khả dụng,
 * hoàn tiền người dẫn đầu cũ và trừ tiền người đặt Auto-Bid nguyên tử (atomic)
 * cho phương thức synchronized {@link Auction#triggerAutoBidsPublic()}.
 */
public class AutoBidStrategy implements BidStrategy {

    private static final AutoBidStrategy INSTANCE = new AutoBidStrategy();

    private AutoBidStrategy() {}

    public static AutoBidStrategy getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(Auction auction, BidTransaction transaction) throws AuctionException {
        Objects.requireNonNull(auction, "Phiên đấu giá (Auction) không được null");

        synchronized (auction) {
            // 1. Kiểm tra thời gian và đóng phiên đúng chuẩn nếu đã hết hạn
            if (LocalDateTime.now().isAfter(auction.getEndTime())) {
                auction.closeAuction();
                throw new AuctionFinishedException("AutoBid: Phiên đấu giá đã kết thúc vào lúc " + auction.getEndTime());
            }

            // 2. Kiểm tra trạng thái hoạt động của phiên đấu giá
            if (auction.getStatus() != AuctionStatus.RUNNING
                    && auction.getStatus() != AuctionStatus.OPEN
                    && auction.getStatus() != AuctionStatus.APPROVED) {
                throw new AuctionClosedException(
                        "AutoBid: Phiên đấu giá không ở trạng thái hoạt động. Trạng thái: " + auction.getStatus()
                );
            }

            // 3. Nếu người gọi truyền trực tiếp một BidTransaction kèm theo (VD: từ BidContext hoặc Unit Test)
            // mà chưa thông qua processBid, thực thi lượt đặt giá đó trước rồi mới kích hoạt chuỗi AutoBid
            if (transaction != null && auction.getAutoBidRules().isEmpty()) {
                auction.processBid(transaction);
            } else {
                // Ủy quyền toàn bộ chuỗi đấu giá tự động nguyên tử cho Auction
                auction.triggerAutoBidsPublic();
            }
        }
    }

    @Override
    public String getStrategyName() {
        return "AutoBidStrategy";
    }
}