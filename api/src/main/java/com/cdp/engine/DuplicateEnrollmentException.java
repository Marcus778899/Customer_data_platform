package com.cdp.engine;

/** 撞 uq_enrollment_dedup（G3）；呼叫端回傳既有實例，不是錯誤。 */
public class DuplicateEnrollmentException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String existingEnrollmentId;

    public DuplicateEnrollmentException(String existingEnrollmentId) {
        super("duplicate enrollment: " + existingEnrollmentId);
        this.existingEnrollmentId = existingEnrollmentId;
    }

    public String existingEnrollmentId() {
        return existingEnrollmentId;
    }
}
