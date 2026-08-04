ALTER TABLE pricing_rule_versions
    ADD CONSTRAINT uq_pricing_rule_versions_tenant_id_id UNIQUE (tenant_id, id);

ALTER TABLE pricing_rules
    ADD COLUMN current_version_id uuid;

UPDATE pricing_rules rule
SET current_version_id = published.id
FROM (
    SELECT DISTINCT ON (tenant_id, pricing_rule_id)
           tenant_id, pricing_rule_id, id
    FROM pricing_rule_versions
    WHERE status = 'PUBLISHED'
    ORDER BY tenant_id, pricing_rule_id, published_at DESC NULLS LAST, version_no DESC
) published
WHERE published.tenant_id = rule.tenant_id
  AND published.pricing_rule_id = rule.id;

ALTER TABLE pricing_rules
    ADD CONSTRAINT fk_pricing_rules_current_version
    FOREIGN KEY (tenant_id, current_version_id)
    REFERENCES pricing_rule_versions(tenant_id, id);
