package com.healthrx.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.healthrx.service.LookupService;
import com.healthrx.web.dto.LookupDtos;

@RestController
@RequestMapping("/api/lookups")
public class LookupController {

    private final LookupService service;

    public LookupController(LookupService service) {
        this.service = service;
    }

    @GetMapping
    public LookupDtos.Lookups lookups() {
        return service.all();
    }
}
