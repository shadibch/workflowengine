/**
 * Starter diagram. A single REST service task with WFE attributes written by
 * hand - this is the round-trip contract the designer must preserve:
 * edit-in-canvas -> export -> re-import must keep `wfe:*` attributes intact.
 */
export const SAMPLE_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                   xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                   xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                   xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xmlns:wfe="http://wfe.example.org/schema/bpmn/wfe"
                   targetNamespace="http://wfe.example.org/schema/bpmn"
                   id="Definitions_BookingDemo">
  <bpmn2:process id="Process_BookingDemo" name="Ticket booking" isExecutable="true">
    <bpmn2:sequenceFlow id="Flow_StartToRest" sourceRef="StartEvent_1" targetRef="Activity_ReserveSeats"/>
    <bpmn2:sequenceFlow id="Flow_RestToEnd" sourceRef="Activity_ReserveSeats" targetRef="EndEvent_1"/>
    <bpmn2:startEvent id="StartEvent_1" name="Request received"/>
    <bpmn2:serviceTask id="Activity_ReserveSeats" name="Reserve seats"
                       wfe:kind="rest"
                       wfe:connection="seats-api"
                       wfe:httpMethod="POST"
                       wfe:operation="/v1/reservations"
                       wfe:timeoutMs="15000"
                       wfe:retries="2"
                       wfe:config="{&quot;requestTemplate&quot;:&quot;showId={{showId}}&amp;seats={{seatCount}}&quot;,&quot;mappings&quot;:{&quot;reservationId&quot;:&quot;$.data.reservationId&quot;}}">
      <bpmn2:incoming>Flow_StartToRest</bpmn2:incoming>
      <bpmn2:outgoing>Flow_RestToEnd</bpmn2:outgoing>
    </bpmn2:serviceTask>
    <bpmn2:endEvent id="EndEvent_1" name="Done"/>
  </bpmn2:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_BookingDemo">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="172" y="102" width="36" height="36"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_ReserveSeats_di" bpmnElement="Activity_ReserveSeats">
        <dc:Bounds x="260" y="80" width="100" height="80"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1">
        <dc:Bounds x="412" y="102" width="36" height="36"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_StartToRest_di" bpmnElement="Flow_StartToRest">
        <di:waypoint x="208" y="120"/>
        <di:waypoint x="260" y="120"/>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_RestToEnd_di" bpmnElement="Flow_RestToEnd">
        <di:waypoint x="360" y="120"/>
        <di:waypoint x="412" y="120"/>
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn2:definitions>
`;