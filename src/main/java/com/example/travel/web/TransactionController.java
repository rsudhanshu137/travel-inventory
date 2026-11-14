package com.example.travel.web;

import com.example.travel.model.Page;
import com.example.travel.model.Transaction;
import com.example.travel.model.TransactionSummary;
import com.example.travel.service.PageSupport;
import com.example.travel.service.TransactionService;
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

/** HTTP only: parse, delegate, shape the status code. No SQL, no business rules. */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService service;
    private final JsonBodies bodies;

    public TransactionController(TransactionService service, JsonBodies bodies) {
        this.service = service;
        this.bodies = bodies;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Transaction> create(@RequestBody String body) {
        Transaction created = service.create(bodies.parse(body));
        return ResponseEntity.status(HttpStatus.CREATED).header("Location", "/transactions/" + created.id()).body(created);
    }

    @GetMapping("/{id}")
    public Transaction get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    public Page<TransactionSummary> list(
            @RequestParam(name = "limit", defaultValue = "" + PageSupport.DEFAULT_LIMIT) int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "external_id", required = false) String externalId) {
        PageSupport.check(limit, offset);
        return service.list(externalId, limit, offset);
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Transaction patch(@PathVariable String id, @RequestBody String body) {
        return service.patch(id, bodies.parse(body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
