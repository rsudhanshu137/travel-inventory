package com.example.travel.web;

import com.example.travel.model.Invoice;
import com.example.travel.model.InvoiceSummary;
import com.example.travel.model.Page;
import com.example.travel.service.InvoiceService;
import com.example.travel.service.PageSupport;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService service;
    private final JsonBodies bodies;

    public InvoiceController(InvoiceService service, JsonBodies bodies) {
        this.service = service;
        this.bodies = bodies;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Invoice> create(@RequestBody String body) {
        Invoice created = service.create(bodies.parse(body));
        return ResponseEntity.status(HttpStatus.CREATED).header("Location", "/invoices/" + created.id()).body(created);
    }

    @GetMapping("/{id}")
    public Invoice get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    public Page<InvoiceSummary> list(
            @RequestParam(name = "limit", defaultValue = "" + PageSupport.DEFAULT_LIMIT) int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        PageSupport.check(limit, offset);
        return service.list(limit, offset);
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Invoice patch(@PathVariable String id, @RequestBody String body) {
        return service.patch(id, bodies.parse(body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
