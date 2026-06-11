package uk.gov.moj.cpp.stagingdlrm.azure.validator;

import java.util.Set;

import com.networknt.schema.ValidationMessage;

public interface Validator<T extends Object> {

    Set<ValidationMessage> validate(T t);
}
