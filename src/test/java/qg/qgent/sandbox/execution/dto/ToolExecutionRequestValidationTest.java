package qg.qgent.sandbox.execution.dto;

import qg.qgent.support.TestIds;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionRequestValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validator = null;
    }

    @Test
    void acceptsRegisteredToolWithUnderscoreInOperationName() {
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setTool("file.edit");

        assertTrue(validator.validate(request).isEmpty());
    }
}
