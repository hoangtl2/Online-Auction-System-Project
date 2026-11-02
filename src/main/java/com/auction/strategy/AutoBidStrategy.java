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
            if (LocalDateTime.now().isAfter(auction.getEndTime())) {
                auction.setStatus(AuctionStatus.FINISHED);
                throw new AuctionFinishedException("AutoBid: Phiên đấu giá đã kết thúc vào lúc " + auction.getEndTime());
            }

            if (auction.getStatus() != AuctionStatus.RUNNING
                    && auction.getStatus() != AuctionStatus.OPEN
                    && auction.getStatus() != AuctionStatus.APPROVED) {
                throw new AuctionClosedException(
                        "AutoBid: Phiên đấu giá không ở trạng thái hoạt động. Trạng thái: " + auction.getStatus()
                );
            }

            // Ủy quyền toàn bộ chuỗi đấu giá tự động nguyên tử cho Auction
            auction.triggerAutoBidsPublic();
        }
    }

    @Override
    public String getStrategyName() {
        return "AutoBidStrategy";
    }
}
