package uk.gov.moj.cpp.stagingdlrm.command.api.util;

public enum ActionTypes {
    CREATE("Create"),
    MIGRATE("Migrate");

    private final String actionName;

    ActionTypes(String actionName) {
        this.actionName = actionName;
    }

    @Override
    public String toString() {
        return actionName;
    }

    public String getActionName() {
        return actionName;
    }
}

