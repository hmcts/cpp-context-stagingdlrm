package uk.gov.moj.cpp.stagingdlrm.azure.event;

import java.util.List;

public record QueueMessage(String queueName, long deliveryCount, List<String> listOfBlobNames) {

}
