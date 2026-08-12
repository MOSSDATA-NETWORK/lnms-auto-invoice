DROP INDEX IF EXISTS public.uq_background_job_unique_key;

CREATE UNIQUE INDEX uq_background_job_unique_key
    ON public.background_jobs(tenant_id, job_type, unique_key)
    WHERE unique_key IS NOT NULL AND status NOT IN ('DEAD', 'CANCELLED');
