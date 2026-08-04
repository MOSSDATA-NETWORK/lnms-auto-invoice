INSERT INTO permissions(permission_code, description) VALUES
('customer.read', 'Read customers and companies'),
('customer.write', 'Create and update customers and companies'),
('contract.write', 'Create and update contracts and contract items'),
('pricing.publish', 'Publish immutable pricing rule versions'),
('usage.sync', 'Synchronize LibreNMS usage'),
('preview.generate', 'Generate invoice previews'),
('preview.adjust', 'Adjust or exclude preview lines'),
('preview.approve.business', 'Approve business review'),
('preview.approve.finance', 'Approve finance review'),
('invoice.finalize', 'Finalize approved invoice previews'),
('invoice.send', 'Send formal invoices'),
('invoice.void', 'Void formal invoices'),
('payment.record', 'Record and allocate payments'),
('template.publish', 'Publish invoice templates'),
('audit.read', 'Read audit records'),
('system.admin', 'Administer system configuration')
ON CONFLICT (permission_code) DO NOTHING;

