package com.capturetotext.app.controller;

import com.capturetotext.app.exception.CaptureNotFoundException;
import com.capturetotext.app.model.Capture;
import com.capturetotext.app.service.CaptureService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private final CaptureService captureService;

    public CaptureController(CaptureService captureService) {
        this.captureService = captureService;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public Capture createCapture(@RequestParam("image") MultipartFile image) {
        return captureService.processAndSave(image);
    }

    @GetMapping
    public Page<Capture> listCaptures(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return captureService.listCaptures(pageable);
    }

    @GetMapping("/{id}")
    public Capture getCapture(@PathVariable String id) {
        return captureService.getCapture(id)
                .orElseThrow(() -> new CaptureNotFoundException(id));
    }
}
