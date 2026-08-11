package com.sqlteacher.desktop.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowStepsTest {

    @Test
    void activeStepShouldNeverBeLessThanOne() {
        WorkflowSteps steps = new WorkflowSteps();
        steps.setActiveStep(0);
        assertEquals(1, steps.getActiveStep());
    }
}
