package com.auction.strategy;

import com.auction.exception.AuctionException;
import com.auction.model.Auction;
import com.auction.model.BidTransaction;

import java.util.Objects;

/**
 * ManualBidStrategy — Chiến lược đặt giá thủ công.
 *
 * Ủy quyền toàn bộ việc kiểm tra điều kiện, trừ/hoàn tiền nguyên tử (atomic)
 * và kích hoạt Auto-Bid cho phương thức synchronized Auction.processBid().
 */
public class ManualBidStrategy implements BidStrategy {

    private static final ManualBidStrategy INSTANCE = new ManualBidStrategy();

    private ManualBidStrategy() {}

    public static ManualBidStrategy getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(Auction auction, BidTransaction transaction) throws AuctionException {
        Objects.requireNonNull(auction, "Phiên đấu giá (Auction) không được null");
        Objects.requireNonNull(transaction, "Giao dịch đặt giá (BidTransaction) không được null");

        // Toàn bộ kiểm tra hợp lệ, hoàn tiền người cũ, trừ tiền người mới,
        // lưu lịch sử và kích hoạt Auto-Bid đều diễn ra nguyên tử trong processBid()
        auction.processBid(transaction);

        System.out.printf(
            "✋ [MANUAL BID] %s đặt $%.2f cho phiên \"%s\" (Số dư còn: $%.2f)%n",
            transaction.getBidder().getUserName(),
            transaction.getBidAmount(),
            auction.getItem().getNameItem(),
            transaction.getBidder().getBalance()
        );
    }

    @Override
    public String getStrategyName() {
        return "ManualBidStrategy";
    }
}