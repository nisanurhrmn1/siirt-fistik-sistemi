-- Veritabanı şeması (19 tablo). Kaynak: db/schema.sql — Python modelleriyle birebir aynı şema.
-- Trigger ve view'lar V2 göçündedir.
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- daily_prices EXCLUDE kısıtı için

CREATE TABLE deduction_types (
	id SERIAL NOT NULL, 
	code VARCHAR(20) NOT NULL, 
	name VARCHAR(100) NOT NULL, 
	calc_method VARCHAR(24) NOT NULL, 
	rate NUMERIC(12, 4) NOT NULL, 
	sort_order INTEGER NOT NULL, 
	is_active BOOLEAN NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_deduction_types PRIMARY KEY (id), 
	CONSTRAINT ck_deduction_types_rate_non_negative CHECK (rate >= 0), 
	CONSTRAINT ck_deduction_types_percent_max CHECK (calc_method <> 'PERCENT' OR rate <= 100), 
	CONSTRAINT uq_deduction_types_code UNIQUE (code), 
	CONSTRAINT ck_deduction_types_calc_method CHECK (calc_method IN ('PERCENT', 'PER_KG', 'FIXED'))
);

CREATE TABLE document_sequences (
	doc_type VARCHAR(10) NOT NULL, 
	year INTEGER NOT NULL, 
	last_value INTEGER NOT NULL, 
	CONSTRAINT pk_document_sequences PRIMARY KEY (doc_type, year)
);

CREATE TABLE producers (
	id SERIAL NOT NULL, 
	producer_type VARCHAR(24) NOT NULL, 
	national_id VARCHAR(11), 
	tax_number VARCHAR(10), 
	tax_office VARCHAR(80), 
	full_name VARCHAR(200) NOT NULL, 
	phone VARCHAR(20) NOT NULL, 
	email VARCHAR(120), 
	address TEXT, 
	district VARCHAR(60), 
	city VARCHAR(60) NOT NULL, 
	is_active BOOLEAN NOT NULL, 
	notes TEXT, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_producers PRIMARY KEY (id), 
	CONSTRAINT ck_producers_identity_required CHECK ((producer_type = 'INDIVIDUAL' AND national_id IS NOT NULL) OR (producer_type = 'COMPANY' AND tax_number IS NOT NULL)), 
	CONSTRAINT ck_producers_national_id_len CHECK (national_id IS NULL OR length(national_id) = 11), 
	CONSTRAINT ck_producers_tax_number_len CHECK (tax_number IS NULL OR length(tax_number) = 10), 
	CONSTRAINT ck_producers_producer_type CHECK (producer_type IN ('INDIVIDUAL', 'COMPANY')), 
	CONSTRAINT uq_producers_national_id UNIQUE (national_id), 
	CONSTRAINT uq_producers_tax_number UNIQUE (tax_number)
);

CREATE TABLE quality_grades (
	id SERIAL NOT NULL, 
	code VARCHAR(10) NOT NULL, 
	name VARCHAR(60) NOT NULL, 
	description TEXT, 
	priority INTEGER NOT NULL, 
	min_split_ratio NUMERIC(5, 2), 
	min_yield_ratio NUMERIC(5, 2), 
	max_defect_ratio NUMERIC(5, 2), 
	max_moisture_ratio NUMERIC(5, 2), 
	is_active BOOLEAN NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_quality_grades PRIMARY KEY (id), 
	CONSTRAINT uq_quality_grades_code UNIQUE (code), 
	CONSTRAINT uq_quality_grades_priority UNIQUE (priority)
);

CREATE TABLE users (
	id SERIAL NOT NULL, 
	username VARCHAR(50) NOT NULL, 
	full_name VARCHAR(120) NOT NULL, 
	role VARCHAR(24) NOT NULL, 
	is_active BOOLEAN NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_users PRIMARY KEY (id), 
	CONSTRAINT uq_users_username UNIQUE (username), 
	CONSTRAINT ck_users_user_role CHECK (role IN ('ADMIN', 'SCALE_OPERATOR', 'QUALITY', 'ACCOUNTING', 'WAREHOUSE', 'PRICE_MANAGER'))
);

CREATE TABLE daily_prices (
	id SERIAL NOT NULL, 
	quality_grade_id INTEGER NOT NULL, 
	price_per_kg NUMERIC(12, 2) NOT NULL, 
	currency VARCHAR(3) NOT NULL, 
	valid_from TIMESTAMP WITH TIME ZONE NOT NULL, 
	valid_to TIMESTAMP WITH TIME ZONE, 
	status VARCHAR(24) NOT NULL, 
	created_by_id INTEGER NOT NULL, 
	approved_by_id INTEGER, 
	approved_at TIMESTAMP WITH TIME ZONE, 
	revoked_at TIMESTAMP WITH TIME ZONE, 
	note TEXT, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_daily_prices PRIMARY KEY (id), 
	CONSTRAINT ck_daily_prices_price_positive CHECK (price_per_kg > 0), 
	CONSTRAINT ck_daily_prices_valid_range CHECK (valid_to IS NULL OR valid_to > valid_from), 
	CONSTRAINT ck_daily_prices_approval_fields CHECK (status = 'DRAFT' OR (approved_by_id IS NOT NULL AND approved_at IS NOT NULL)), 
	CONSTRAINT ck_daily_prices_revoked_fields CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)), 
	CONSTRAINT fk_daily_prices_quality_grade_id_quality_grades FOREIGN KEY(quality_grade_id) REFERENCES quality_grades (id), 
	CONSTRAINT ck_daily_prices_price_status CHECK (status IN ('DRAFT', 'APPROVED', 'REVOKED')), 
	CONSTRAINT fk_daily_prices_created_by_id_users FOREIGN KEY(created_by_id) REFERENCES users (id), 
	CONSTRAINT fk_daily_prices_approved_by_id_users FOREIGN KEY(approved_by_id) REFERENCES users (id), 
	CONSTRAINT ex_daily_prices_no_overlap EXCLUDE USING gist (quality_grade_id WITH =, tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)') WITH &&) WHERE (status = 'APPROVED')
);

CREATE INDEX ix_daily_prices_lookup ON daily_prices (quality_grade_id, status, valid_from);

CREATE TABLE producer_bank_accounts (
	id SERIAL NOT NULL, 
	producer_id INTEGER NOT NULL, 
	bank_name VARCHAR(80) NOT NULL, 
	iban VARCHAR(26) NOT NULL, 
	account_holder VARCHAR(200) NOT NULL, 
	is_default BOOLEAN NOT NULL, 
	is_active BOOLEAN NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_producer_bank_accounts PRIMARY KEY (id), 
	CONSTRAINT uq_producer_bank_accounts_producer_iban UNIQUE (producer_id, iban), 
	CONSTRAINT ck_producer_bank_accounts_iban_format CHECK (length(iban) = 26 AND substr(iban, 1, 2) = 'TR'), 
	CONSTRAINT fk_producer_bank_accounts_producer_id_producers FOREIGN KEY(producer_id) REFERENCES producers (id) ON DELETE RESTRICT
);

CREATE INDEX ix_producer_bank_accounts_producer_id ON producer_bank_accounts (producer_id);

CREATE UNIQUE INDEX uq_producer_bank_accounts_one_default ON producer_bank_accounts (producer_id) WHERE is_default = true;

CREATE TABLE silos (
	id SERIAL NOT NULL, 
	code VARCHAR(20) NOT NULL, 
	name VARCHAR(80) NOT NULL, 
	location VARCHAR(120), 
	capacity_kg NUMERIC(12, 2) NOT NULL, 
	dedicated_grade_id INTEGER, 
	is_active BOOLEAN NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_silos PRIMARY KEY (id), 
	CONSTRAINT ck_silos_capacity_positive CHECK (capacity_kg > 0), 
	CONSTRAINT uq_silos_code UNIQUE (code), 
	CONSTRAINT fk_silos_dedicated_grade_id_quality_grades FOREIGN KEY(dedicated_grade_id) REFERENCES quality_grades (id)
);

CREATE TABLE advances (
	id SERIAL NOT NULL, 
	advance_no VARCHAR(20) NOT NULL, 
	producer_id INTEGER NOT NULL, 
	amount NUMERIC(14, 2) NOT NULL, 
	offset_amount NUMERIC(14, 2) NOT NULL, 
	status VARCHAR(24) NOT NULL, 
	given_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	method VARCHAR(24) NOT NULL, 
	bank_account_id INTEGER, 
	description TEXT, 
	created_by_id INTEGER NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_advances PRIMARY KEY (id), 
	CONSTRAINT ck_advances_amount_positive CHECK (amount > 0), 
	CONSTRAINT ck_advances_offset_range CHECK (offset_amount >= 0 AND offset_amount <= amount), 
	CONSTRAINT ck_advances_bank_required CHECK (method <> 'BANK_TRANSFER' OR bank_account_id IS NOT NULL), 
	CONSTRAINT uq_advances_advance_no UNIQUE (advance_no), 
	CONSTRAINT fk_advances_producer_id_producers FOREIGN KEY(producer_id) REFERENCES producers (id) ON DELETE RESTRICT, 
	CONSTRAINT ck_advances_advance_status CHECK (status IN ('OPEN', 'PARTIALLY_OFFSET', 'CLOSED')), 
	CONSTRAINT ck_advances_payment_method CHECK (method IN ('BANK_TRANSFER', 'CASH', 'CHEQUE')), 
	CONSTRAINT fk_advances_bank_account_id_producer_bank_accounts FOREIGN KEY(bank_account_id) REFERENCES producer_bank_accounts (id), 
	CONSTRAINT fk_advances_created_by_id_users FOREIGN KEY(created_by_id) REFERENCES users (id)
);

CREATE INDEX ix_advances_open_fifo ON advances (producer_id, given_at);

CREATE INDEX ix_advances_producer_id ON advances (producer_id);

CREATE TABLE payments (
	id SERIAL NOT NULL, 
	payment_no VARCHAR(20) NOT NULL, 
	producer_id INTEGER NOT NULL, 
	bank_account_id INTEGER, 
	amount NUMERIC(14, 2) NOT NULL, 
	method VARCHAR(24) NOT NULL, 
	paid_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	reference_no VARCHAR(60), 
	description TEXT, 
	created_by_id INTEGER NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_payments PRIMARY KEY (id), 
	CONSTRAINT ck_payments_amount_positive CHECK (amount > 0), 
	CONSTRAINT ck_payments_bank_required CHECK (method <> 'BANK_TRANSFER' OR bank_account_id IS NOT NULL), 
	CONSTRAINT uq_payments_payment_no UNIQUE (payment_no), 
	CONSTRAINT fk_payments_producer_id_producers FOREIGN KEY(producer_id) REFERENCES producers (id) ON DELETE RESTRICT, 
	CONSTRAINT fk_payments_bank_account_id_producer_bank_accounts FOREIGN KEY(bank_account_id) REFERENCES producer_bank_accounts (id), 
	CONSTRAINT ck_payments_payment_method CHECK (method IN ('BANK_TRANSFER', 'CASH', 'CHEQUE')), 
	CONSTRAINT fk_payments_created_by_id_users FOREIGN KEY(created_by_id) REFERENCES users (id)
);

CREATE INDEX ix_payments_producer_id ON payments (producer_id);

CREATE TABLE weigh_tickets (
	id SERIAL NOT NULL, 
	ticket_no VARCHAR(20) NOT NULL, 
	producer_id INTEGER NOT NULL, 
	vehicle_plate VARCHAR(12) NOT NULL, 
	driver_name VARCHAR(120), 
	gross_kg NUMERIC(12, 2) NOT NULL, 
	tare_kg NUMERIC(12, 2), 
	net_kg NUMERIC(12, 2) GENERATED ALWAYS AS (gross_kg - tare_kg) STORED, 
	gross_weighed_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	tare_weighed_at TIMESTAMP WITH TIME ZONE, 
	weighed_by_id INTEGER NOT NULL, 
	status VARCHAR(24) NOT NULL, 
	daily_price_id INTEGER, 
	unit_price NUMERIC(12, 2), 
	priced_at TIMESTAMP WITH TIME ZONE, 
	cancel_reason TEXT, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_weigh_tickets PRIMARY KEY (id), 
	CONSTRAINT ck_weigh_tickets_gross_positive CHECK (gross_kg > 0), 
	CONSTRAINT ck_weigh_tickets_tare_range CHECK (tare_kg IS NULL OR (tare_kg >= 0 AND tare_kg < gross_kg)), 
	CONSTRAINT ck_weigh_tickets_tare_after_gross CHECK (tare_weighed_at IS NULL OR tare_weighed_at >= gross_weighed_at), 
	CONSTRAINT ck_weigh_tickets_tare_required CHECK (status IN ('GROSS_WEIGHED', 'CANCELLED') OR tare_kg IS NOT NULL), 
	CONSTRAINT ck_weigh_tickets_price_snapshot_complete CHECK ((daily_price_id IS NULL AND unit_price IS NULL AND priced_at IS NULL) OR (daily_price_id IS NOT NULL AND unit_price IS NOT NULL AND priced_at IS NOT NULL)), 
	CONSTRAINT ck_weigh_tickets_priced_when_graded CHECK (status NOT IN ('GRADED', 'SETTLED') OR daily_price_id IS NOT NULL), 
	CONSTRAINT ck_weigh_tickets_cancel_reason CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL), 
	CONSTRAINT uq_weigh_tickets_ticket_no UNIQUE (ticket_no), 
	CONSTRAINT fk_weigh_tickets_producer_id_producers FOREIGN KEY(producer_id) REFERENCES producers (id) ON DELETE RESTRICT, 
	CONSTRAINT fk_weigh_tickets_weighed_by_id_users FOREIGN KEY(weighed_by_id) REFERENCES users (id), 
	CONSTRAINT ck_weigh_tickets_ticket_status CHECK (status IN ('GROSS_WEIGHED', 'NET_WEIGHED', 'GRADED', 'SETTLED', 'CANCELLED')), 
	CONSTRAINT fk_weigh_tickets_daily_price_id_daily_prices FOREIGN KEY(daily_price_id) REFERENCES daily_prices (id)
);

CREATE INDEX ix_weigh_tickets_producer_time ON weigh_tickets (producer_id, gross_weighed_at);

CREATE INDEX ix_weigh_tickets_time ON weigh_tickets (gross_weighed_at);

CREATE TABLE lots (
	id SERIAL NOT NULL, 
	lot_no VARCHAR(20) NOT NULL, 
	ticket_id INTEGER NOT NULL, 
	quality_grade_id INTEGER NOT NULL, 
	initial_kg NUMERIC(12, 2) NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_lots PRIMARY KEY (id), 
	CONSTRAINT ck_lots_initial_positive CHECK (initial_kg > 0), 
	CONSTRAINT uq_lots_lot_no UNIQUE (lot_no), 
	CONSTRAINT uq_lots_ticket_id UNIQUE (ticket_id), 
	CONSTRAINT fk_lots_ticket_id_weigh_tickets FOREIGN KEY(ticket_id) REFERENCES weigh_tickets (id) ON DELETE RESTRICT, 
	CONSTRAINT fk_lots_quality_grade_id_quality_grades FOREIGN KEY(quality_grade_id) REFERENCES quality_grades (id)
);

CREATE TABLE quality_inspections (
	id SERIAL NOT NULL, 
	ticket_id INTEGER NOT NULL, 
	sample_weight_g NUMERIC(8, 2) NOT NULL, 
	split_ratio NUMERIC(5, 2) NOT NULL, 
	yield_ratio NUMERIC(5, 2) NOT NULL, 
	empty_ratio NUMERIC(5, 2) NOT NULL, 
	rotten_ratio NUMERIC(5, 2) NOT NULL, 
	moisture_ratio NUMERIC(5, 2) NOT NULL, 
	quality_grade_id INTEGER NOT NULL, 
	is_manual_override BOOLEAN NOT NULL, 
	override_reason TEXT, 
	inspected_by_id INTEGER NOT NULL, 
	inspected_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_quality_inspections PRIMARY KEY (id), 
	CONSTRAINT ck_quality_inspections_sample_positive CHECK (sample_weight_g > 0), 
	CONSTRAINT ck_quality_inspections_ratios_range CHECK (split_ratio BETWEEN 0 AND 100 AND yield_ratio BETWEEN 0 AND 100 AND empty_ratio BETWEEN 0 AND 100 AND rotten_ratio BETWEEN 0 AND 100 AND moisture_ratio BETWEEN 0 AND 100), 
	CONSTRAINT ck_quality_inspections_defect_sum CHECK (empty_ratio + rotten_ratio <= 100), 
	CONSTRAINT ck_quality_inspections_override_reason CHECK (NOT is_manual_override OR override_reason IS NOT NULL), 
	CONSTRAINT uq_quality_inspections_ticket_id UNIQUE (ticket_id), 
	CONSTRAINT fk_quality_inspections_ticket_id_weigh_tickets FOREIGN KEY(ticket_id) REFERENCES weigh_tickets (id) ON DELETE RESTRICT, 
	CONSTRAINT fk_quality_inspections_quality_grade_id_quality_grades FOREIGN KEY(quality_grade_id) REFERENCES quality_grades (id), 
	CONSTRAINT fk_quality_inspections_inspected_by_id_users FOREIGN KEY(inspected_by_id) REFERENCES users (id)
);

CREATE TABLE settlements (
	id SERIAL NOT NULL, 
	settlement_no VARCHAR(20) NOT NULL, 
	ticket_id INTEGER NOT NULL, 
	producer_id INTEGER NOT NULL, 
	net_kg NUMERIC(12, 2) NOT NULL, 
	unit_price NUMERIC(12, 2) NOT NULL, 
	gross_amount NUMERIC(14, 2) NOT NULL, 
	deductions_total NUMERIC(14, 2) NOT NULL, 
	advance_offset_total NUMERIC(14, 2) NOT NULL, 
	net_payable NUMERIC(14, 2) NOT NULL, 
	paid_amount NUMERIC(14, 2) NOT NULL, 
	status VARCHAR(24) NOT NULL, 
	created_by_id INTEGER NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_settlements PRIMARY KEY (id), 
	CONSTRAINT ck_settlements_qty_price_positive CHECK (net_kg > 0 AND unit_price > 0), 
	CONSTRAINT ck_settlements_deductions_range CHECK (deductions_total >= 0 AND deductions_total <= gross_amount), 
	CONSTRAINT ck_settlements_offset_non_negative CHECK (advance_offset_total >= 0), 
	CONSTRAINT ck_settlements_net_non_negative CHECK (net_payable >= 0), 
	CONSTRAINT ck_settlements_paid_range CHECK (paid_amount >= 0 AND paid_amount <= net_payable), 
	CONSTRAINT ck_settlements_gross_formula CHECK (gross_amount = ROUND(net_kg * unit_price, 2)), 
	CONSTRAINT ck_settlements_net_formula CHECK (net_payable = gross_amount - deductions_total - advance_offset_total), 
	CONSTRAINT uq_settlements_settlement_no UNIQUE (settlement_no), 
	CONSTRAINT uq_settlements_ticket_id UNIQUE (ticket_id), 
	CONSTRAINT fk_settlements_ticket_id_weigh_tickets FOREIGN KEY(ticket_id) REFERENCES weigh_tickets (id) ON DELETE RESTRICT, 
	CONSTRAINT fk_settlements_producer_id_producers FOREIGN KEY(producer_id) REFERENCES producers (id) ON DELETE RESTRICT, 
	CONSTRAINT ck_settlements_settlement_status CHECK (status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID', 'OFFSET_ONLY')), 
	CONSTRAINT fk_settlements_created_by_id_users FOREIGN KEY(created_by_id) REFERENCES users (id)
);

CREATE INDEX ix_settlements_producer_id ON settlements (producer_id);

CREATE TABLE advance_offsets (
	id SERIAL NOT NULL, 
	advance_id INTEGER NOT NULL, 
	settlement_id INTEGER NOT NULL, 
	amount NUMERIC(14, 2) NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_advance_offsets PRIMARY KEY (id), 
	CONSTRAINT uq_advance_offsets_pair UNIQUE (advance_id, settlement_id), 
	CONSTRAINT ck_advance_offsets_amount_positive CHECK (amount > 0), 
	CONSTRAINT fk_advance_offsets_advance_id_advances FOREIGN KEY(advance_id) REFERENCES advances (id) ON DELETE RESTRICT, 
	CONSTRAINT fk_advance_offsets_settlement_id_settlements FOREIGN KEY(settlement_id) REFERENCES settlements (id) ON DELETE RESTRICT
);

CREATE TABLE payment_allocations (
	id SERIAL NOT NULL, 
	payment_id INTEGER NOT NULL, 
	settlement_id INTEGER NOT NULL, 
	amount NUMERIC(14, 2) NOT NULL, 
	CONSTRAINT pk_payment_allocations PRIMARY KEY (id), 
	CONSTRAINT uq_payment_allocations_pair UNIQUE (payment_id, settlement_id), 
	CONSTRAINT ck_payment_allocations_amount_positive CHECK (amount > 0), 
	CONSTRAINT fk_payment_allocations_payment_id_payments FOREIGN KEY(payment_id) REFERENCES payments (id) ON DELETE RESTRICT, 
	CONSTRAINT fk_payment_allocations_settlement_id_settlements FOREIGN KEY(settlement_id) REFERENCES settlements (id) ON DELETE RESTRICT
);

CREATE INDEX ix_payment_allocations_settlement_id ON payment_allocations (settlement_id);

CREATE TABLE settlement_deductions (
	id SERIAL NOT NULL, 
	settlement_id INTEGER NOT NULL, 
	deduction_type_id INTEGER NOT NULL, 
	calc_method VARCHAR(24) NOT NULL, 
	rate NUMERIC(12, 4) NOT NULL, 
	base_amount NUMERIC(14, 2) NOT NULL, 
	amount NUMERIC(14, 2) NOT NULL, 
	CONSTRAINT pk_settlement_deductions PRIMARY KEY (id), 
	CONSTRAINT uq_settlement_deductions_type UNIQUE (settlement_id, deduction_type_id), 
	CONSTRAINT ck_settlement_deductions_amount_non_negative CHECK (amount >= 0), 
	CONSTRAINT fk_settlement_deductions_settlement_id_settlements FOREIGN KEY(settlement_id) REFERENCES settlements (id) ON DELETE CASCADE, 
	CONSTRAINT fk_settlement_deductions_deduction_type_id_deduction_types FOREIGN KEY(deduction_type_id) REFERENCES deduction_types (id), 
	CONSTRAINT ck_settlement_deductions_calc_method CHECK (calc_method IN ('PERCENT', 'PER_KG', 'FIXED'))
);

CREATE TABLE stock_movements (
	id BIGSERIAL NOT NULL, 
	lot_id INTEGER NOT NULL, 
	silo_id INTEGER NOT NULL, 
	movement_type VARCHAR(24) NOT NULL, 
	quantity_kg NUMERIC(12, 2) NOT NULL, 
	moved_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	transfer_group VARCHAR(36), 
	note TEXT, 
	created_by_id INTEGER NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_stock_movements PRIMARY KEY (id), 
	CONSTRAINT ck_stock_movements_type_sign CHECK ((movement_type IN ('RECEIPT', 'TRANSFER_IN') AND quantity_kg > 0) OR (movement_type IN ('DISPATCH', 'TRANSFER_OUT') AND quantity_kg < 0) OR (movement_type = 'ADJUSTMENT' AND quantity_kg <> 0)), 
	CONSTRAINT ck_stock_movements_transfer_group_required CHECK (movement_type NOT IN ('TRANSFER_IN', 'TRANSFER_OUT') OR transfer_group IS NOT NULL), 
	CONSTRAINT fk_stock_movements_lot_id_lots FOREIGN KEY(lot_id) REFERENCES lots (id) ON DELETE RESTRICT, 
	CONSTRAINT fk_stock_movements_silo_id_silos FOREIGN KEY(silo_id) REFERENCES silos (id) ON DELETE RESTRICT, 
	CONSTRAINT ck_stock_movements_stock_movement_type CHECK (movement_type IN ('RECEIPT', 'DISPATCH', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUSTMENT')), 
	CONSTRAINT fk_stock_movements_created_by_id_users FOREIGN KEY(created_by_id) REFERENCES users (id)
);

CREATE INDEX ix_stock_movements_lot ON stock_movements (lot_id);

CREATE INDEX ix_stock_movements_silo ON stock_movements (silo_id, lot_id);

CREATE TABLE producer_ledger_entries (
	id BIGSERIAL NOT NULL, 
	producer_id INTEGER NOT NULL, 
	entry_type VARCHAR(24) NOT NULL, 
	amount NUMERIC(14, 2) NOT NULL, 
	entry_at TIMESTAMP WITH TIME ZONE NOT NULL, 
	description VARCHAR(255), 
	ticket_id INTEGER, 
	settlement_id INTEGER, 
	settlement_deduction_id INTEGER, 
	advance_id INTEGER, 
	payment_id INTEGER, 
	reversal_of_id BIGINT, 
	created_by_id INTEGER NOT NULL, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL, 
	CONSTRAINT pk_producer_ledger_entries PRIMARY KEY (id), 
	CONSTRAINT ck_producer_ledger_entries_type_sign_ref CHECK ((entry_type = 'DELIVERY' AND amount > 0 AND settlement_id IS NOT NULL) OR (entry_type = 'DEDUCTION' AND amount < 0 AND settlement_deduction_id IS NOT NULL) OR (entry_type = 'ADVANCE' AND amount < 0 AND advance_id IS NOT NULL) OR (entry_type = 'PAYMENT' AND amount < 0 AND payment_id IS NOT NULL) OR (entry_type = 'ADJUSTMENT' AND amount <> 0) OR (entry_type = 'REVERSAL' AND amount <> 0 AND reversal_of_id IS NOT NULL)), 
	CONSTRAINT fk_producer_ledger_entries_producer_id_producers FOREIGN KEY(producer_id) REFERENCES producers (id) ON DELETE RESTRICT, 
	CONSTRAINT ck_producer_ledger_entries_ledger_entry_type CHECK (entry_type IN ('DELIVERY', 'DEDUCTION', 'ADVANCE', 'PAYMENT', 'ADJUSTMENT', 'REVERSAL')), 
	CONSTRAINT fk_producer_ledger_entries_ticket_id_weigh_tickets FOREIGN KEY(ticket_id) REFERENCES weigh_tickets (id), 
	CONSTRAINT fk_producer_ledger_entries_settlement_id_settlements FOREIGN KEY(settlement_id) REFERENCES settlements (id), 
	CONSTRAINT fk_producer_ledger_entries_settlement_deduction_id_sett_f5f0 FOREIGN KEY(settlement_deduction_id) REFERENCES settlement_deductions (id), 
	CONSTRAINT fk_producer_ledger_entries_advance_id_advances FOREIGN KEY(advance_id) REFERENCES advances (id), 
	CONSTRAINT fk_producer_ledger_entries_payment_id_payments FOREIGN KEY(payment_id) REFERENCES payments (id), 
	CONSTRAINT uq_producer_ledger_entries_reversal_of_id UNIQUE (reversal_of_id), 
	CONSTRAINT fk_producer_ledger_entries_reversal_of_id_producer_ledg_ce04 FOREIGN KEY(reversal_of_id) REFERENCES producer_ledger_entries (id), 
	CONSTRAINT fk_producer_ledger_entries_created_by_id_users FOREIGN KEY(created_by_id) REFERENCES users (id)
);

CREATE INDEX ix_ledger_producer_time ON producer_ledger_entries (producer_id, entry_at);
