/**
 * Ligero validation: annotation-based request validation via Jakarta Bean
 * Validation (Hibernate Validator).
 */
module com.ligero.validation {
    requires transitive com.ligero.core;
    requires jakarta.validation;
    requires org.hibernate.validator;

    exports com.ligero.validate;

    provides com.ligero.spi.RequestValidator with com.ligero.validate.BeanValidationRequestValidator;
}
