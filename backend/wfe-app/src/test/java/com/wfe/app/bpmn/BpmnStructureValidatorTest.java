package com.wfe.app.bpmn;

import com.wfe.core.error.WorkflowValidationException;
import com.wfe.core.error.WorkflowValidationException.Problem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnStructureValidatorTest {

    private final BpmnStructureValidator validator = new BpmnStructureValidator();

    private static String process(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  id="Definitions_1" targetNamespace="https://wfe.dev/bpmn">
                  <bpmn:process id="Process_1" name="Test" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(body);
    }

    private static final String START_TO_END = """
                    <bpmn:startEvent id="Start_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:endEvent id="End_1">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="End_1"/>
            """;

    /** Just the codes, for the many assertions that care about which rules fired, not their text. */
    private List<String> codes(String xml) {
        return validator.validate(xml).stream().map(Problem::code).toList();
    }

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        void aMinimalProcess() {
            assertThat(validator.validate(process(START_TO_END))).isEmpty();
        }

        @Test
        void theSkeletonItself() {
            // The document every new definition starts from must pass its own
            // validator, or every new definition would be unpublishable.
            String xml = BpmnSkeleton.minimalProcess("order-fulfilment", "Order Fulfilment &amp; Dispatch");
            assertThat(validator.validate(xml)).isEmpty();
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @Test
        void malformedXml() {
            assertThat(codes("<definitions><process>"))
                    .containsExactly("WFE-1001");
        }

        @Test
        void aDocumentThatIsNotBpmn() {
            assertThat(codes("<html><body/></html>"))
                    .containsExactly("WFE-1002");
        }

        @Test
        void aFileWithNoProcess() {
            assertThat(codes("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"/>"))
                    .containsExactly("WFE-1003");
        }

        @Test
        void aProcessWithNoStart() {
            String xml = process("""
                        <bpmn:endEvent id="End_1"/>
                    """);
            assertThat(codes(xml)).contains("WFE-1006");
        }

        @Test
        void aProcessWithNoEnd() {
            String xml = process("""
                        <bpmn:startEvent id="Start_1"/>
                    """);
            assertThat(codes(xml)).contains("WFE-1007");
        }

        @Test
        void aFlowToNowhere() {
            String xml = process("""
                        <bpmn:startEvent id="Start_1"/>
                        <bpmn:endEvent id="End_1"/>
                        <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Ghost"/>
                    """);
            List<Problem> problems = validator.validate(xml);
            assertThat(problems).extracting(Problem::code).contains("WFE-1012");
            // The problem must name the flow that is broken, or the canvas cannot
            // put a marker on it.
            assertThat(problems).filteredOn(p -> p.code().equals("WFE-1012"))
                    .extracting(Problem::nodeId).containsExactly("Flow_1");
        }

        @Test
        void duplicatedElementIds() {
            String xml = process("""
                        <bpmn:startEvent id="Same"/>
                        <bpmn:endEvent id="End_1"/>
                        <bpmn:startEvent id="Same"/>
                    """);
            assertThat(codes(xml)).contains("WFE-1009");
        }

        @Test
        void aFlowWhoseOutgoingContradictsItsTarget() {
            String xml = process("""
                        <bpmn:startEvent id="Start_1"/>
                        <bpmn:endEvent id="End_1"/>
                        <bpmn:endEvent id="End_2"/>
                        <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="End_1">
                          <bpmn:outgoing>End_2</bpmn:outgoing>
                        </bpmn:sequenceFlow>
                    """);
            assertThat(codes(xml)).contains("WFE-1014");
        }
    }

    @Nested
    @DisplayName("warns without blocking")
    class Warns {

        @Test
        void aboutNonExecutableProcesses() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                      id="Definitions_1" targetNamespace="https://wfe.dev/bpmn">
                      <bpmn:process id="Process_1" isExecutable="false">
                        <bpmn:startEvent id="Start_1"/>
                        <bpmn:endEvent id="End_1"/>
                      </bpmn:process>
                    </bpmn:definitions>
                    """;
            List<Problem> problems = validator.validate(xml);
            assertThat(problems).extracting(Problem::code).containsExactly("WFE-1005");
            assertThat(problems).noneMatch(Problem::isBlocking);
            assertThat(BpmnStructureValidator.isPublishable(problems)).isTrue();
        }
    }

    @Test
    @DisplayName("validateOrThrow throws with every problem attached")
    void throwsWithAllProblems() {
        String xml = process("""
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Ghost" targetRef="AlsoGhost"/>
                """);
        assertThatThrownBy(() -> validator.validateOrThrow(xml))
                .isInstanceOf(WorkflowValidationException.class)
                .satisfies(thrown -> assertThat(((WorkflowValidationException) thrown).problems())
                        .extracting(Problem::code)
                        .contains("WFE-1006", "WFE-1007", "WFE-1011", "WFE-1012"));
    }

    @Test
    @DisplayName("a document with a DOCTYPE is refused outright")
    void refusesDoctype() {
        // An XXE attempt: without the hardening flags this would read a local file
        // and hand back its contents in the parse error.
        String xxe = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE definitions [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  id="Definitions_1" targetNamespace="https://wfe.dev/bpmn">
                  <bpmn:process id="Process_1" isExecutable="true">
                    <bpmn:startEvent id="Start_1"/>
                    <bpmn:endEvent id="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        assertThat(codes(xxe)).containsExactly("WFE-1001");
    }

    @Test
    @DisplayName("the checksum ignores formatting but not content")
    void checksumIsCanonical() {
        String xml = process(START_TO_END);
        String reformatted = process(START_TO_END.replace("\n", "\n\n"));

        // Same diagram, different indentation: publishing it again must be a no-op.
        assertThat(BpmnDocuments.checksum(xml)).isEqualTo(BpmnDocuments.checksum(reformatted));

        String renamed = process(START_TO_END.replace("End_1", "End_2"));
        assertThat(BpmnDocuments.checksum(xml)).isNotEqualTo(BpmnDocuments.checksum(renamed));
    }

    @Test
    @DisplayName("checksum of a half-written document is stable rather than fatal")
    void checksumSurvivesMalformedInput() {
        // Autosave must be able to store a document mid-edit, so a broken draft still
        // gets an identity instead of failing the save.
        String broken = "<definitions><process";
        assertThat(BpmnDocuments.checksum(broken)).hasSize(64);
        assertThat(BpmnDocuments.checksum(broken)).isEqualTo(BpmnDocuments.checksum(broken));
    }
}
