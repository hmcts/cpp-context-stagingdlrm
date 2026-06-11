package uk.gov.moj.cpp.stagingdlrm.event.processor.domain;


public enum MigratedGender {
    NOT_KNOWN(0, "NOT_KNOWN"),
    MALE(1, "MALE"),
    FEMALE(2, "FEMALE"),
    NOT_SPECIFIED(9, "NOT_SPECIFIED");

    private final int code;
    private final String value;

    MigratedGender(int code, String value) {
        this.code = code;
        this.value = value;
    }

    public int getCode() {
        return code;
    }

    public String getValue() {
        return value;
    }

    public static String getValueFromCode(Integer code) {
        for (MigratedGender gender : values()) {
            if (gender.code == code) {
                return gender.value;
            }
        }
        return code.toString();
    }

}
