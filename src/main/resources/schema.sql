-- Money: NUMERIC(19,4) everywhere (never FLOAT/DOUBLE). The API normalises every amount to the
-- currency's minor-unit scale (EUR/USD = 2, JPY = 0, KWD = 3) and rejects extra precision.
-- Timestamps: TIMESTAMP WITH TIME ZONE, always written as UTC.
-- Written to be re-runnable (IF NOT EXISTS) and to stay close to portable SQL (Postgres-friendly).

-- ---------------------------------------------------------------- transactions (STI + child tables)
CREATE TABLE IF NOT EXISTS transactions (
    id                       VARCHAR(40)    NOT NULL PRIMARY KEY,
    seq                      BIGINT         GENERATED ALWAYS AS IDENTITY,   -- stable list ordering
    type                     VARCHAR(16)    NOT NULL,
    external_id              VARCHAR(200)   NOT NULL,
    currency                 CHAR(3)        NOT NULL,
    occurred_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    total                    NUMERIC(19,4)  NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,

    -- flight (coupons live in flight_coupons)
    flight_origin            VARCHAR(64),
    flight_destination       VARCHAR(64),
    flight_fare              NUMERIC(19,4),

    -- hotel
    hotel_name               VARCHAR(200),
    hotel_street             VARCHAR(200),
    hotel_city               VARCHAR(100),
    hotel_country            VARCHAR(100),
    hotel_address_extra_json VARCHAR(4000),   -- optional extra address keys (postal_code, ...)
    hotel_check_in           DATE,
    hotel_check_out          DATE,
    hotel_nights             INTEGER,
    hotel_room_rate          NUMERIC(19,4),
    hotel_extras_json        VARCHAR(8000),   -- optional [{name, amount}]

    -- navan_fee
    fee_kind                 VARCHAR(32),
    fee_description          VARCHAR(500),
    fee_related_external_id  VARCHAR(200),

    -- any additional top-level metadata keys the client sent ("extra fields are fine")
    extra_json               VARCHAR(8000),

    -- THE duplicate-identity guarantee: enforced by the database, not by application code.
    CONSTRAINT uq_transactions_external_id UNIQUE (external_id),
    CONSTRAINT ck_transactions_type CHECK (type IN ('flight', 'hotel', 'rail', 'navan_fee')),
    CONSTRAINT ck_transactions_total CHECK (total >= 0),
    CONSTRAINT ck_transactions_flight CHECK (type <> 'flight' OR
        (flight_origin IS NOT NULL AND flight_destination IS NOT NULL AND flight_fare IS NOT NULL)),
    CONSTRAINT ck_transactions_hotel CHECK (type <> 'hotel' OR
        (hotel_name IS NOT NULL AND hotel_street IS NOT NULL AND hotel_city IS NOT NULL
         AND hotel_country IS NOT NULL AND hotel_check_in IS NOT NULL AND hotel_check_out IS NOT NULL
         AND hotel_nights IS NOT NULL AND hotel_room_rate IS NOT NULL)),
    CONSTRAINT ck_transactions_navan_fee CHECK (type <> 'navan_fee' OR fee_kind IN ('trip_fee', 'agent_call_fee'))
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_transactions_seq ON transactions (seq);

CREATE TABLE IF NOT EXISTS transaction_tax_lines (
    transaction_id VARCHAR(40)   NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INTEGER       NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    rate           NUMERIC(10,6),
    amount         NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (transaction_id, position)
);

CREATE TABLE IF NOT EXISTS transaction_fee_lines (
    transaction_id VARCHAR(40)   NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INTEGER       NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (transaction_id, position)
);

CREATE TABLE IF NOT EXISTS flight_coupons (
    transaction_id VARCHAR(40)  NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INTEGER      NOT NULL,
    carrier        VARCHAR(16),
    flight_number  VARCHAR(16)  NOT NULL,
    from_airport   VARCHAR(64)  NOT NULL,
    to_airport     VARCHAR(64)  NOT NULL,
    departure      TIMESTAMP WITH TIME ZONE NOT NULL,
    arrival        TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (transaction_id, position)
);

CREATE TABLE IF NOT EXISTS rail_legs (
    transaction_id VARCHAR(40)  NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INTEGER      NOT NULL,
    origin         VARCHAR(100) NOT NULL,
    destination    VARCHAR(100) NOT NULL,
    departure      TIMESTAMP WITH TIME ZONE NOT NULL,
    arrival        TIMESTAMP WITH TIME ZONE NOT NULL,
    train_number   VARCHAR(32),
    travel_class   VARCHAR(32),
    PRIMARY KEY (transaction_id, position)
);

-- ---------------------------------------------------------------- invoices (frozen snapshot)
CREATE TABLE IF NOT EXISTS invoices (
    id         VARCHAR(40)   NOT NULL PRIMARY KEY,
    seq        BIGINT        GENERATED ALWAYS AS IDENTITY,
    currency   CHAR(3)       NOT NULL,
    total      NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_invoices_seq ON invoices (seq);

-- One row per included transaction. Everything below is a COPY taken at invoicing time - the only
-- link back to the live row is transaction_id, which exists for "is this transaction billed?".
CREATE TABLE IF NOT EXISTS invoice_lines (
    line_id        VARCHAR(40)   NOT NULL PRIMARY KEY,
    invoice_id     VARCHAR(40)   NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    transaction_id VARCHAR(40)   NOT NULL REFERENCES transactions (id) ON DELETE RESTRICT,
    position       INTEGER       NOT NULL,
    type           VARCHAR(16)   NOT NULL,
    external_id    VARCHAR(200)  NOT NULL,
    currency       CHAR(3)       NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    total          NUMERIC(19,4) NOT NULL,
    metadata_json  VARCHAR(100000) NOT NULL,
    -- the same transaction cannot be double-counted on one invoice (DB-enforced)
    CONSTRAINT uq_invoice_lines_invoice_txn UNIQUE (invoice_id, transaction_id)
);
-- "is this transaction on any invoice?" -- NOT unique: a transaction may be on several invoices.
CREATE INDEX IF NOT EXISTS ix_invoice_lines_transaction_id ON invoice_lines (transaction_id);

CREATE TABLE IF NOT EXISTS invoice_line_tax_lines (
    line_id  VARCHAR(40)   NOT NULL REFERENCES invoice_lines (line_id) ON DELETE CASCADE,
    position INTEGER       NOT NULL,
    name     VARCHAR(100)  NOT NULL,
    rate     NUMERIC(10,6),
    amount   NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (line_id, position)
);

CREATE TABLE IF NOT EXISTS invoice_line_fee_lines (
    line_id  VARCHAR(40)   NOT NULL REFERENCES invoice_lines (line_id) ON DELETE CASCADE,
    position INTEGER       NOT NULL,
    name     VARCHAR(100)  NOT NULL,
    amount   NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (line_id, position)
);
