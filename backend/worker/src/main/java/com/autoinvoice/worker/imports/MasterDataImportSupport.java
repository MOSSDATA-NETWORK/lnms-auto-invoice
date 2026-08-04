package com.autoinvoice.worker.imports;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class MasterDataImportSupport {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,99}");
    private static final Pattern CUSTOMER_CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,63}");
    private static final Set<String> CUSTOMER_TYPES = Set.of("ENTERPRISE", "INDIVIDUAL", "RESELLER", "INTERNAL");
    private static final Set<String> CUSTOMER_STATUSES = Set.of("PROSPECT", "ACTIVE", "SUSPENDED", "ARCHIVED");
    private static final Set<String> COMPANY_STATUSES = Set.of("ACTIVE", "SUSPENDED", "ARCHIVED");
    private static final Set<String> SERVICE_STATUSES = Set.of("PENDING", "ACTIVE", "SUSPENDED", "ENDED", "CANCELLED");
    private static final Set<String> CONTRACT_STATUSES = Set.of(
            "DRAFT", "PENDING_APPROVAL", "ACTIVE", "SUSPENDED", "EXPIRED", "TERMINATED", "VOIDED");
    private static final Set<String> ITEM_STATUSES = Set.of("DRAFT", "ACTIVE", "SUSPENDED", "ENDED", "CANCELLED");
    private final JdbcClient jdbc;

    public MasterDataImportSupport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> requiredHeaders(String importType) {
        return switch (importType) {
            case "CUSTOMERS" -> Set.of("customer_no", "customer_name");
            case "COMPANIES" -> Set.of("customer_no", "company_code", "company_name");
            case "SERVICES" -> Set.of("service_no", "customer_no", "company_code", "service_name", "service_type");
            case "CONTRACTS" -> Set.of("contract_no", "customer_no", "company_code", "contract_name",
                    "effective_from", "currency_code");
            case "CONTRACT_ITEMS" -> Set.of("contract_item_no", "contract_no", "service_no",
                    "pricing_rule_code", "item_name", "billing_type", "effective_from");
            default -> throw new IllegalArgumentException("Unsupported import type: " + importType);
        };
    }

    public Context loadContext(UUID tenantId) {
        Map<String, UUID> customers = jdbc.sql("SELECT customer_no, id FROM customers WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId).query((rs, row) -> Map.entry(rs.getString(1), rs.getObject(2, UUID.class)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, CompanyRef> companies = jdbc.sql("""
                        SELECT company.company_code, company.id, customer.customer_no
                        FROM companies company JOIN customers customer
                          ON customer.tenant_id = company.tenant_id AND customer.id = company.customer_id
                        WHERE company.tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((rs, row) -> Map.entry(rs.getString(1),
                        new CompanyRef(rs.getObject(2, UUID.class), rs.getString(3))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, UUID> products = jdbc.sql("SELECT product_code, id FROM products WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId).query((rs, row) -> Map.entry(rs.getString(1), rs.getObject(2, UUID.class)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, ContractRef> contracts = jdbc.sql("""
                        SELECT contract.contract_no, contract.id, customer.customer_no, company.company_code
                        FROM contracts contract
                        JOIN customers customer ON customer.tenant_id = contract.tenant_id AND customer.id = contract.customer_id
                        JOIN companies company ON company.tenant_id = contract.tenant_id AND company.id = contract.company_id
                        WHERE contract.tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((rs, row) -> Map.entry(rs.getString(1), new ContractRef(rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, ServiceRef> services = jdbc.sql("""
                        SELECT service.service_no, service.id, customer.customer_no, company.company_code
                        FROM services service
                        JOIN customers customer ON customer.tenant_id = service.tenant_id AND customer.id = service.customer_id
                        JOIN companies company ON company.tenant_id = service.tenant_id AND company.id = service.company_id
                        WHERE service.tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((rs, row) -> Map.entry(rs.getString(1), new ServiceRef(rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, UUID> pricingRules = jdbc.sql("SELECT rule_code, id FROM pricing_rules WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId).query((rs, row) -> Map.entry(rs.getString(1), rs.getObject(2, UUID.class)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<String> currencies = Set.copyOf(jdbc.sql("SELECT code FROM currencies").query(String.class).list());
        return new Context(customers, companies, products, contracts, services, pricingRules, currencies);
    }

    public List<RowError> validate(String importType, LinkedHashMap<String, String> row, Context context) {
        List<RowError> errors = new ArrayList<>();
        requiredHeaders(importType).forEach(field -> required(row, field, errors));
        if (!errors.isEmpty()) {
            return errors;
        }
        switch (importType) {
            case "CUSTOMERS" -> validateCustomer(row, context, errors);
            case "COMPANIES" -> validateCompany(row, context, errors);
            case "SERVICES" -> validateService(row, context, errors);
            case "CONTRACTS" -> validateContract(row, context, errors);
            case "CONTRACT_ITEMS" -> validateContractItem(row, context, errors);
            default -> errors.add(new RowError(null, "IMPORT_TYPE_UNSUPPORTED", "Import type is unsupported"));
        }
        return errors;
    }

    private void validateCustomer(Map<String, String> row, Context context, List<RowError> errors) {
        pattern(row, "customer_no", CUSTOMER_CODE, errors);
        enumeration(row, "customer_type", CUSTOMER_TYPES, "ENTERPRISE", errors);
        currency(row, "default_currency", "CNY", context, errors);
        enumeration(row, "status", CUSTOMER_STATUSES, "ACTIVE", errors);
        nonNegativeInteger(row, "default_payment_terms_days", "7", errors);
    }

    private void validateCompany(Map<String, String> row, Context context, List<RowError> errors) {
        pattern(row, "company_code", CODE, errors);
        reference(context.customers().containsKey(value(row, "customer_no")), "customer_no",
                "Referenced customer does not exist", errors);
        currency(row, "default_currency", "", context, errors);
        decimalBetween(row, "default_tax_rate", BigDecimal.ZERO, BigDecimal.ONE, errors);
        enumeration(row, "status", COMPANY_STATUSES, "ACTIVE", errors);
    }

    private void validateService(Map<String, String> row, Context context, List<RowError> errors) {
        pattern(row, "service_no", CODE, errors);
        CompanyRef company = context.companies().get(value(row, "company_code"));
        reference(context.customers().containsKey(value(row, "customer_no")), "customer_no",
                "Referenced customer does not exist", errors);
        reference(company != null, "company_code", "Referenced company does not exist", errors);
        if (company != null && !company.customerNo().equals(value(row, "customer_no"))) {
            errors.add(new RowError("company_code", "REFERENCE_MISMATCH",
                    "Company does not belong to the referenced customer"));
        }
        String productCode = value(row, "product_code");
        if (!productCode.isBlank()) {
            reference(context.products().containsKey(productCode), "product_code", "Referenced product does not exist", errors);
        }
        date(row, "activated_on", errors);
        date(row, "deactivated_on", errors);
        compareDates(row, "activated_on", "deactivated_on", errors);
        enumeration(row, "status", SERVICE_STATUSES, "PENDING", errors);
    }

    private void validateContract(Map<String, String> row, Context context, List<RowError> errors) {
        pattern(row, "contract_no", CODE, errors);
        CompanyRef company = context.companies().get(value(row, "company_code"));
        reference(context.customers().containsKey(value(row, "customer_no")), "customer_no",
                "Referenced customer does not exist", errors);
        reference(company != null, "company_code", "Referenced company does not exist", errors);
        if (company != null && !company.customerNo().equals(value(row, "customer_no"))) {
            errors.add(new RowError("company_code", "REFERENCE_MISMATCH",
                    "Company does not belong to the referenced customer"));
        }
        date(row, "effective_from", errors);
        date(row, "effective_to", errors);
        compareDates(row, "effective_from", "effective_to", errors);
        currency(row, "currency_code", "", context, errors);
        decimalBetween(row, "tax_rate", BigDecimal.ZERO, BigDecimal.ONE, errors);
        nonNegativeInteger(row, "payment_terms_days", "7", errors);
        integerBetween(row, "billing_day", 1, 28, errors);
        bool(row, "auto_renew", errors);
        bool(row, "tax_inclusive", errors);
        enumeration(row, "status", CONTRACT_STATUSES, "DRAFT", errors);
    }

    private void validateContractItem(Map<String, String> row, Context context, List<RowError> errors) {
        pattern(row, "contract_item_no", CODE, errors);
        ContractRef contract = context.contracts().get(value(row, "contract_no"));
        ServiceRef service = context.services().get(value(row, "service_no"));
        reference(contract != null, "contract_no", "Referenced contract does not exist", errors);
        reference(service != null, "service_no", "Referenced service does not exist", errors);
        reference(context.pricingRules().containsKey(value(row, "pricing_rule_code")), "pricing_rule_code",
                "Referenced pricing rule does not exist", errors);
        if (contract != null && service != null
                && (!contract.customerNo().equals(service.customerNo())
                || !contract.companyCode().equals(service.companyCode()))) {
            errors.add(new RowError("service_no", "REFERENCE_MISMATCH",
                    "Service and contract must belong to the same customer and company"));
        }
        offsetDateTime(row, "effective_from", errors);
        offsetDateTime(row, "effective_to", errors);
        compareOffsetDateTimes(row, "effective_from", "effective_to", errors);
        decimal(row, "default_quantity", errors);
        bool(row, "auto_bill", errors);
        bool(row, "visible_on_invoice", errors);
        nonNegativeInteger(row, "sort_order", "0", errors);
        enumeration(row, "status", ITEM_STATUSES, "DRAFT", errors);
    }

    private void required(Map<String, String> row, String field, List<RowError> errors) {
        if (value(row, field).isBlank()) {
            errors.add(new RowError(field, "REQUIRED", "Field is required"));
        }
    }

    private void pattern(Map<String, String> row, String field, Pattern pattern, List<RowError> errors) {
        if (!pattern.matcher(value(row, field)).matches()) {
            errors.add(new RowError(field, "FORMAT_INVALID", "Field has an invalid code format"));
        }
    }

    private void reference(boolean exists, String field, String message, List<RowError> errors) {
        if (!exists) {
            errors.add(new RowError(field, "REFERENCE_NOT_FOUND", message));
        }
    }

    private void currency(Map<String, String> row, String field, String fallback, Context context,
                          List<RowError> errors) {
        String value = value(row, field, fallback);
        if (!value.isBlank() && !context.currencies().contains(value)) {
            errors.add(new RowError(field, "CURRENCY_INVALID", "Currency code is not configured"));
        }
    }

    private void enumeration(Map<String, String> row, String field, Set<String> allowed, String fallback,
                             List<RowError> errors) {
        String value = value(row, field, fallback);
        if (!allowed.contains(value)) {
            errors.add(new RowError(field, "VALUE_UNSUPPORTED", "Field contains an unsupported value"));
        }
    }

    private void date(Map<String, String> row, String field, List<RowError> errors) {
        String value = value(row, field);
        if (value.isBlank()) {
            return;
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            errors.add(new RowError(field, "DATE_INVALID", "Date must use YYYY-MM-DD"));
        }
    }

    private void offsetDateTime(Map<String, String> row, String field, List<RowError> errors) {
        String value = value(row, field);
        if (value.isBlank()) {
            return;
        }
        try {
            OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            errors.add(new RowError(field, "DATETIME_INVALID", "Timestamp must be RFC 3339 with an offset"));
        }
    }

    private void compareDates(Map<String, String> row, String startField, String endField, List<RowError> errors) {
        try {
            String end = value(row, endField);
            if (!end.isBlank() && !LocalDate.parse(value(row, startField)).isBefore(LocalDate.parse(end))) {
                errors.add(new RowError(endField, "PERIOD_INVALID", "End date must be after start date"));
            }
        } catch (DateTimeParseException ignored) {
        }
    }

    private void compareOffsetDateTimes(Map<String, String> row, String startField, String endField,
                                        List<RowError> errors) {
        try {
            String end = value(row, endField);
            if (!end.isBlank() && !OffsetDateTime.parse(value(row, startField)).isBefore(OffsetDateTime.parse(end))) {
                errors.add(new RowError(endField, "PERIOD_INVALID", "End timestamp must be after start timestamp"));
            }
        } catch (DateTimeParseException ignored) {
        }
    }

    private void decimal(Map<String, String> row, String field, List<RowError> errors) {
        String value = value(row, field);
        if (value.isBlank()) {
            return;
        }
        try {
            new BigDecimal(value);
        } catch (NumberFormatException exception) {
            errors.add(new RowError(field, "DECIMAL_INVALID", "Field must be a decimal string"));
        }
    }

    private void decimalBetween(Map<String, String> row, String field, BigDecimal minimum, BigDecimal maximum,
                                List<RowError> errors) {
        String value = value(row, field);
        if (value.isBlank()) {
            return;
        }
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0) {
                errors.add(new RowError(field, "DECIMAL_RANGE_INVALID", "Decimal is outside the allowed range"));
            }
        } catch (NumberFormatException exception) {
            errors.add(new RowError(field, "DECIMAL_INVALID", "Field must be a decimal string"));
        }
    }

    private void nonNegativeInteger(Map<String, String> row, String field, String fallback, List<RowError> errors) {
        integer(row, field, fallback, 0, Integer.MAX_VALUE, errors);
    }

    private void integerBetween(Map<String, String> row, String field, int minimum, int maximum,
                                List<RowError> errors) {
        String raw = value(row, field);
        if (raw.isBlank()) {
            return;
        }
        integer(row, field, raw, minimum, maximum, errors);
    }

    private void integer(Map<String, String> row, String field, String fallback, int minimum, int maximum,
                         List<RowError> errors) {
        try {
            int number = Integer.parseInt(value(row, field, fallback));
            if (number < minimum || number > maximum) {
                errors.add(new RowError(field, "INTEGER_RANGE_INVALID", "Integer is outside the allowed range"));
            }
        } catch (NumberFormatException exception) {
            errors.add(new RowError(field, "INTEGER_INVALID", "Field must be an integer"));
        }
    }

    private void bool(Map<String, String> row, String field, List<RowError> errors) {
        String value = value(row, field);
        if (!value.isBlank() && !Set.of("true", "false", "1", "0", "yes", "no").contains(value.toLowerCase())) {
            errors.add(new RowError(field, "BOOLEAN_INVALID", "Boolean must be true/false, 1/0 or yes/no"));
        }
    }

    public String value(Map<String, String> row, String field) {
        return value(row, field, "");
    }

    public String value(Map<String, String> row, String field, String fallback) {
        String value = row.get(field);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public boolean booleanValue(Map<String, String> row, String field, boolean fallback) {
        String value = value(row, field);
        return value.isBlank() ? fallback : Set.of("true", "1", "yes").contains(value.toLowerCase());
    }

    public record RowError(String field, String code, String message) {
    }

    public record Context(Map<String, UUID> customers, Map<String, CompanyRef> companies,
                          Map<String, UUID> products, Map<String, ContractRef> contracts,
                          Map<String, ServiceRef> services, Map<String, UUID> pricingRules,
                          Set<String> currencies) {
    }

    public record CompanyRef(UUID id, String customerNo) {
    }

    public record ContractRef(UUID id, String customerNo, String companyCode) {
    }

    public record ServiceRef(UUID id, String customerNo, String companyCode) {
    }
}
