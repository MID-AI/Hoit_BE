package com.ll.demo03.domain.bookmark.controller;

import com.ll.demo03.domain.adminImage.dto.AdminImageResponse;
import com.ll.demo03.domain.bookmark.entity.Bookmark;
import com.ll.demo03.domain.bookmark.service.BookmarkService;
import com.ll.demo03.global.dto.GlobalResponse;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    @PostMapping("/{adminImageId}")
    public GlobalResponse addBookmark(
            @PathVariable(name="adminImageId") Long adminImageId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        bookmarkService.addBookmark(adminImageId, userDetails.getUsername());
        return GlobalResponse.success("북마크 등록에 성공했습니다.");
    }

    @DeleteMapping("/{adminImageId}")
    public GlobalResponse deleteBookmark(
            @PathVariable(name="adminImageId") Long adminImageId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        bookmarkService.deleteBookmark(adminImageId, userDetails.getUsername());
        return GlobalResponse.success("북마크 삭제에 성공했습니다.");
    }

    @GetMapping("/my")
    public GlobalResponse<?> getMyBookmarks(@AuthenticationPrincipal UserDetails userDetails) {
        List<AdminImageResponse> bookmarks = bookmarkService.getMyBookmarks(userDetails.getUsername());
        return GlobalResponse.success(bookmarks);
    }
}
