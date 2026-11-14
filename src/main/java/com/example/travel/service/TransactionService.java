package com.example.travel.service;

import com.example.travel.model.ApiException;
import com.example.travel.model.Page;
import com.example.travel.model.Transaction;
import com.example.travel.model.TransactionDraft;
import com.example.travel.model.TransactionSummary;
import com.example.travel.persistence.DuplicateIdentity;
import com.example.travel.persistence.TransactionRepository;
import com.example.travel.validation.TransactionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionService {

    private final TransactionValidator validator;
    private final TransactionRepository repository;
    private final DuplicateIdentity identity;
    private final TransactionTemplate tx;

    public TransactionService(TransactionValidator validator, TransactionRepository repository,
                              DuplicateIdentity identity, TransactionTemplate tx) {
        this.validator = validator;
        this.repository = repository;
        this.identity = identity;
        this.tx = tx;
    }

    /**
     * Create. Order matters:
     * <ol>
     *   <li>cheap shape check (type + external_id)</li>
     *   <li>duplicate fast path -> 409 with the EXISTING id, before deep validation, so a replayed POST is always
     *       a duplicate even if the replay body is thinner than the original (e.g. empty coupons)</li>
     *   <li>deep validation -> 400</li>
     *   <li>one DB transaction inserting header + tax + fee + detail rows; the UNIQUE(external_id) constraint is the
     *       real guard, so a concurrent twin that slipped past step 2 fails here and is mapped to the same 409</li>
     * </ol>
     */
    public Transaction create(JsonNode body) {
        validator.requireType(body);
        String externalId = validator.requireExternalId(body);

        identity.existingId(externalId).ifPresent(existing -> {
            throw ApiException.duplicateExternalId(externalId, existing);
        });

        TransactionDraft draft = validator.validateCreate(body);
        String id = "txn_" + UUID.randomUUID().toString().replace("-", "");
        try {
            tx.executeWithoutResult(status -> repository.insert(id, draft, Instant.now()));
        } catch (DataAccessException failure) {
            // Normally a DuplicateKeyException from UNIQUE(external_id): we lost the race and the insert rolled back.
            // Whatever the driver called it, the definitive question is "does a row with this external_id exist now?"
            String existing = identity.existingId(externalId).orElseThrow(() -> failure);
            throw ApiException.duplicateExternalId(externalId, existing);
        }
        return get(id);
    }

    public Transaction get(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("transaction '" + id + "' not found"));
    }

    public Page<TransactionSummary> list(String externalId, int limit, int offset) {
        List<TransactionSummary> rows = repository.list(externalId, limit + 1, offset);
        return PageSupport.page(rows, limit, offset);
    }

    /**
     * PATCH: lock the row, refuse if billed, validate, replace header + child rows -- all in one DB transaction.
     * The row lock is the same one invoicing takes, so "check not invoiced" cannot race with "add to invoice".
     */
    public Transaction patch(String id, JsonNode patch) {
        tx.executeWithoutResult(status -> {
            if (!repository.lock(id)) throw ApiException.notFound("transaction '" + id + "' not found");
            if (repository.isInvoiced(id)) throw invoiced(id, "modified");
            Transaction existing = repository.findById(id).orElseThrow();
            TransactionDraft draft = validator.validatePatch(existing, patch);
            repository.replace(id, draft, Instant.now());
        });
        return get(id);
    }

    public void delete(String id) {
        tx.executeWithoutResult(status -> {
            if (!repository.lock(id)) throw ApiException.notFound("transaction '" + id + "' not found");
            if (repository.isInvoiced(id)) throw invoiced(id, "deleted");
            repository.delete(id); // FK ON DELETE RESTRICT on invoice_lines is the backstop
        });
    }

    private static ApiException invoiced(String id, String verb) {
        return ApiException.conflict("transaction_invoiced",
                "transaction '" + id + "' is on an invoice and cannot be " + verb);
    }
}
