package com.auction.utils;

import com.auction.dao.IAuctionDAO;
import com.auction.model.Auction;
import com.auction.model.AuctionStatus;
import com.auction.model.Item;
import com.auction.model.Seller;
import com.auction.server.ServerApp;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Lớp Quản lý Phiên đấu giá (Tầng Service/Manager).
 * Điều phối nghiệp vụ trung gian giữa Controller/Server và tầng DAO.
 */
public class AuctionManager {

    // BẮT BUỘC có volatile để Double-Checked Locking hoạt động đúng trong môi trường đa luồng
    private static volatile AuctionManager instance;

    // volatile đảm bảo khi luồng khác hoặc Unit Test setAuctionDAO, mọi luồng đều nhìn thấy ngay lập tức
    private volatile IAuctionDAO auctionDAO;

    private AuctionManager() {
    }

    public static AuctionManager getInstance() {
        if (instance == null) {
            synchronized (AuctionManager.class) {
                if (instance == null) {
                    instance = new AuctionManager();
                }
            }
        }
        return instance;
    }

    /**
     * Tiêm (Inject) Interface DAO vào Manager (Phục vụ Dependency Injection & Unit Testing).
     */
    public void setAuctionDAO(IAuctionDAO auctionDAO) {
        this.auctionDAO = auctionDAO;
    }

    /**
     * Quyết định nguồn dữ liệu xử lý an toàn.
     */
    private IAuctionDAO getDAO() {
        IAuctionDAO dao = this.auctionDAO;
        if (dao != null) {
            return dao;
        }
        dao = ServerApp.getAuctionDAO();
        if (dao == null) {
            throw new IllegalStateException(
                    "IAuctionDAO chưa được khởi tạo! Hãy gọi setAuctionDAO() hoặc khởi động ServerApp trước.");
        }
        return dao;
    }

    /**
     * Tạo phiên đấu giá mới với đầy đủ kiểm tra toàn vẹn nghiệp vụ (Business Validation).
     */
    public Auction createAuction(Item item, Seller seller, LocalDateTime start, LocalDateTime end) {
        Objects.requireNonNull(item, "Sản phẩm đấu giá (Item) không được null");
        Objects.requireNonNull(seller, "Người bán (Seller) không được null");
        Objects.requireNonNull(start, "Thời gian bắt đầu (startTime) không được null");
        Objects.requireNonNull(end, "Thời gian kết thúc (endTime) không được null");

        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Thời gian kết thúc phiên đấu giá phải sau thời gian bắt đầu!");
        }

        if (item.getStartingPrice() < 0) {
            throw new IllegalArgumentException("Giá khởi điểm của sản phẩm không được âm!");
        }

        Auction newAuction = new Auction(item, seller, start, end);
        getDAO().save(newAuction);
        return newAuction;
    }

    /**
     * Lấy danh sách toàn bộ phiên đấu giá (Trả về danh sách bất biến, chống NullPointerException).
     */
    public List<Auction> getAllAuctions() {
        List<Auction> auctions = getDAO().findAll();
        if (auctions == null || auctions.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(auctions);
    }

    /**
     * Lọc danh sách phiên đấu giá theo trạng thái (VD: RUNNING, PENDING_APPROVAL).
     */
    public List<Auction> getAuctionsByStatus(AuctionStatus status) {
        if (status == null) {
            return Collections.emptyList();
        }
        return getAllAuctions().stream()
                .filter(a -> a.getStatus() == status)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Tìm phiên đấu giá theo ID.
     */
    public Auction getAuctionById(String auctionId) {
        if (auctionId == null || auctionId.isBlank()) {
            return null;
        }
        return getDAO().findById(auctionId);
    }

    /**
     * Cập nhật thông tin phiên đấu giá xuống tầng lưu trữ.
     */
    public void updateAuction(Auction auction) {
        Objects.requireNonNull(auction, "Phiên đấu giá cần cập nhật không được null");
        getDAO().update(auction);
    }
}