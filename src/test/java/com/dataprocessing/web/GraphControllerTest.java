package com.dataprocessing.web;

import com.dataprocessing.domain.GraphEdge;
import com.dataprocessing.domain.SensitiveClassification;
import com.dataprocessing.repository.SeenTriplesGraphRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphControllerTest {

    @Mock
    private SeenTriplesGraphRepository graphRepository;

    @Mock
    private HttpServletRequest request;

    private GraphController controller;

    @BeforeEach
    void setUp() {
        controller = new GraphController(graphRepository);
    }

    @Test
    void returnsNodesAndEdgesForKnownAccount() {
        when(request.getAttribute("accountId")).thenReturn("acct-1");
        when(graphRepository.findEdgesByAccount("acct-1")).thenReturn(List.of(
                new GraphEdge("users", "payment", Set.of(SensitiveClassification.CREDIT_CARD_NUMBER)),
                new GraphEdge("users", "auth", Set.of(SensitiveClassification.SOCIAL_SECURITY_NUMBER,
                                                       SensitiveClassification.FIRST_NAME))
        ));

        ResponseEntity<GraphResponse> response = controller.getGraph(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.nodes()).extracting(GraphResponse.VisNode::id)
                .containsExactly("users", "payment", "auth");
        assertThat(body.edges()).hasSize(2);
        GraphResponse.VisEdge firstEdge = body.edges().get(0);
        assertThat(firstEdge.from()).isEqualTo("users");
        assertThat(firstEdge.to()).isEqualTo("payment");
        assertThat(firstEdge.label()).isEqualTo("CREDIT_CARD_NUMBER");
    }

    @Test
    void returnsEmptyGraphWhenNoTriplesSeen() {
        when(request.getAttribute("accountId")).thenReturn("acct-empty");
        when(graphRepository.findEdgesByAccount("acct-empty")).thenReturn(List.of());

        ResponseEntity<GraphResponse> response = controller.getGraph(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().nodes()).isEmpty();
        assertThat(response.getBody().edges()).isEmpty();
    }

    @Test
    void classificationLabelIsSortedAlphabetically() {
        when(request.getAttribute("accountId")).thenReturn("acct-1");
        when(graphRepository.findEdgesByAccount("acct-1")).thenReturn(List.of(
                new GraphEdge("a", "b", Set.of(
                        SensitiveClassification.SOCIAL_SECURITY_NUMBER,
                        SensitiveClassification.FIRST_NAME,
                        SensitiveClassification.CREDIT_CARD_NUMBER
                ))
        ));

        ResponseEntity<GraphResponse> response = controller.getGraph(request);
        String label = response.getBody().edges().get(0).label();

        assertThat(label).isEqualTo("CREDIT_CARD_NUMBER,FIRST_NAME,SOCIAL_SECURITY_NUMBER");
    }

    @Test
    void returnsBadRequestWhenAccountIdMissing() {
        when(request.getAttribute("accountId")).thenReturn(null);

        ResponseEntity<GraphResponse> response = controller.getGraph(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nodeDeduplicationAcrossMultipleEdges() {
        when(request.getAttribute("accountId")).thenReturn("acct-1");
        // "users" appears as source in both edges
        when(graphRepository.findEdgesByAccount("acct-1")).thenReturn(List.of(
                new GraphEdge("users", "payment", Set.of(SensitiveClassification.CREDIT_CARD_NUMBER)),
                new GraphEdge("users", "billing", Set.of(SensitiveClassification.FIRST_NAME))
        ));

        ResponseEntity<GraphResponse> response = controller.getGraph(request);
        List<GraphResponse.VisNode> nodes = response.getBody().nodes();

        // "users" must appear exactly once
        assertThat(nodes).extracting(GraphResponse.VisNode::id)
                .containsExactlyInAnyOrder("users", "payment", "billing");
        assertThat(nodes.stream().filter(n -> n.id().equals("users")).count()).isEqualTo(1);
    }
}
