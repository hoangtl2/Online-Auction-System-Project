package com.auction.utils;

import com.auction.dao.IAuctionDAO;
import com.auction.model.Auction;
import com.auction.model.Electronics;
import com.auction.model.Item;
import com.auction.model.Seller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuctionManagerTest {

    @Mock
    private IAuctionDAO mockAuctionDAO;

    private AuctionManager auctionManager;

    @BeforeEach
    void setUp() {
        auctionManager = AuctionManager.getInstance();
        auctionManager.setAuctionDAO(mockAuctionDAO);
    }

    @Test
    void testCreateAuction() {
        Seller seller = new Seller("seller1", "123", "s@gmail.com");
        Item laptop = new Electronics("Macbook", "M3 Pro - 16GB RAM", 2000.0, "Apple", 12);
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusDays(1);

        // ĐÃ SỬA LỖI 2: Hàm save() trong IGenericDAO trả về boolean chứ không phải void!
        when(mockAuctionDAO.save(any(Auction.class))).thenReturn(true);

        Auction createdAuction = auctionManager.createAuction(laptop, seller, start, end);

        assertNotNull(createdAuction);
        assertEquals("Macbook", createdAuction.getItem().getNameItem());
        verify(mockAuctionDAO, times(1)).save(any(Auction.class));
    }

    @Test
    void testGetAuctionById() {
        // ĐÃ SỬA LỖI 3: Tạo dữ liệu hợp lệ để constructor Auction không ném lỗi null-check
        Item dummyItem = new Electronics("Dummy", "Desc", 0.0, "Brand", 12);
        Seller seller = new Seller("seller1", "123", "s@gmail.com");
        LocalDateTime start = LocalDateTime.now();
        Auction mockAuction = new Auction(dummyItem, seller, start, start.plusHours(1));

        when(mockAuctionDAO.findById("A123")).thenReturn(mockAuction);

        Auction result = auctionManager.getAuctionById("A123");

        assertNotNull(result);
        verify(mockAuctionDAO, times(1)).findById("A123");
    }
}