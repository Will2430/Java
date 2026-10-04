package com.capturetotext.app.controller;

import com.capturetotext.app.exception.CaptureNotFoundException;
import com.capturetotext.app.model.Capture;
import com.capturetotext.app.service.CaptureService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// The user is always the verified token's subject ("sub" claim), never an id sent in the request.
@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private final CaptureService captureService;

    public CaptureController(CaptureService captureService) {
        this.captureService = captureService;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Capture createCapture(@RequestParam("image") MultipartFile image, @AuthenticationPrincipal Jwt jwt) {
        return captureService.submitForProcessing(image, jwt.getSubject());
    }

    @GetMapping
    public Page<Capture> listCaptures(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return captureService.listCaptures(jwt.getSubject(), pageable);
    }

    @GetMapping("/{id}")
    public Capture getCapture(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return captureService.getCapture(id, jwt.getSubject())
                .orElseThrow(() -> new CaptureNotFoundException(id));
    }
}
