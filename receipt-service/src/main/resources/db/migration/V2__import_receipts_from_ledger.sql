-- One-off import of the receipts the ledger created before receipts became their own service, so existing
-- transfers keep a downloadable receipt. It is a no-op on a fresh database, or when the ledger has already
-- dropped its receipt_* columns. Deploy the receipt service before the ledger stops carrying those columns.
DO $$
BEGIN
    IF to_regclass('public.transfers') IS NOT NULL AND to_regclass('public.accounts') IS NOT NULL
       AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'transfers' AND column_name = 'receipt_status') THEN
        INSERT INTO receipts (transfer_id, type, transfer_created_at, source_iban, source_owner, target_iban, target_owner,
                              amount, currency, status, object_key, details_sha256, pdf_sha256, attempts)
        SELECT t.id, t.type, t.created_at, s.iban, s.owner_name, d.iban, d.owner_name,
               t.amount, t.currency, t.receipt_status, t.receipt_object_key, t.receipt_sha256, t.receipt_pdf_sha256,
               t.receipt_attempts
          FROM public.transfers t
          JOIN public.accounts s ON s.id = t.source_account_id
          JOIN public.accounts d ON d.id = t.target_account_id
        ON CONFLICT (transfer_id) DO NOTHING;
    END IF;
END
$$;
