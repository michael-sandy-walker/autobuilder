package com.walpro.autobuilder.agent.analysis;

import java.util.function.Function;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.config.SystemCapabilityManifest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.query.GraphQueryRequest;
import com.walpro.autobuilder.graph.query.GraphQueryRequestParser;
import com.walpro.autobuilder.graph.query.GraphQueryResult;
import com.walpro.autobuilder.graph.query.GraphQueryService;
import com.walpro.autobuilder.protocol.analysis.AnalysisProtocolContract;
import com.walpro.autobuilder.protocol.analysis.AnalysisResponseParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisLLMAdapter {

    private final AnalysisResponseParser parser = new AnalysisResponseParser();
    private final GraphQueryRequestParser queryParser = new GraphQueryRequestParser();

    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker protocolInvoker;
    private final GraphQueryService graphQueryService;
    private final SystemCapabilityManifest capabilityManifest;

    public AnalysisOutcome analyze(CycleContextData context) {
        String systemPrompt = AnalysisProtocolContract.getSystemPrompt()
                + "\n\n" + capabilityManifest.render();

        // PASS 1: allow either final analysis outcome OR a single #GraphQuery request
        String userPrompt1 = AnalysisProtocolContract.getUserPrompt(context) + pass1Addon();

        LLMResponseResult<String> pass1 = protocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt1,
                this::isValidPass1,
                Function.identity()
        );

        String content1 = (pass1 != null && pass1.outcome() != null) ? pass1.outcome() : "";
        GraphQueryRequest req = queryParser.parse(content1);

        if (req == null || !req.requested()) {
            if (AnalysisProtocolContract.isValidResponse(content1)) {
                return parser.parse(content1);
            }
            return AnalysisOutcome.empty("LLM response did not follow analysis protocol and did not request a graph query.");
        }

        // Execute ONE guarded read
        GraphQueryResult result = graphQueryService.execute(req, context);

        // PASS 2: provide results and force final format; disallow further queries
        String userPrompt2 = AnalysisProtocolContract.getUserPrompt(context)
                + "\n\n--- GRAPH QUERY REQUEST (sanitized) ---\n"
                + "Type: " + safe(req.type()) + "\n"
                + "Limit: " + req.limit() + "\n"
                + "\n--- GRAPH QUERY RESULT ---\n"
                + result.renderedBlock()
                + """

                Now produce the final response using the exact required format.
                Do NOT request another graph query.
                """;

        LLMResponseResult<AnalysisOutcome> pass2 = protocolInvoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt2,
                this::isValidPass2,
                parser::parse
        );

        if (pass2 != null && pass2.success() && pass2.outcome() != null) {
            return pass2.outcome();
        }

        return AnalysisOutcome.empty("LLM response did not follow analysis protocol after graph query.");
    }

    private boolean isValidPass1(String content) {
        if (content == null || content.isBlank()) return false;

        GraphQueryRequest req = queryParser.parse(content);
        if (req != null && req.requested()) {
            // pass 1 may be a query request (and MUST then not include the final analysis sections)
            return true;
        }

        // otherwise, must be a valid final analysis response
        return AnalysisProtocolContract.isValidResponse(content);
    }

    private boolean isValidPass2(String content) {
        if (content == null || content.isBlank()) return false;

        GraphQueryRequest req = queryParser.parse(content);
        if (req != null && req.requested()) {
            return false; // forbid chaining queries
        }

        return AnalysisProtocolContract.isValidResponse(content);
    }

    /**
     * Phase A: keep it minimal and stable.
     * Only allow ORCH_SNAPSHOT, no keyword search, no free-form queries.
     */
    private String pass1Addon() {
        return """

            Request ONE guarded graph query using:

            #GraphQuery
            Type: ORCH_SNAPSHOT | DIAGNOSTIC_SCAN
            Limit: <1..50>

            If you include #GraphQuery, do NOT output the final analysis response yet.
            """;
    }

//    private String formatResult(GraphQueryResult result) {
//        if (result == null) return "(no result)";
//        if (result.rows() == null || result.rows().isEmpty()) return "(empty)";
//
//        StringBuilder sb = new StringBuilder();
//        sb.append("Type: ").append(result.request() != null ? safe(result.request().type()) : "(n/a)").append("\n");
//        sb.append("Rows: ").append(result.rows().size()).append("\n");
//
//        int i = 0;
//        for (var row : result.rows()) {
//            if (i++ >= 20) {
//                sb.append("... (truncated)\n");
//                break;
//            }
//            sb.append("- ").append(row).append("\n");
//        }
//        return sb.toString();
//    }

    private static String safe(Object o) {
        return o == null ? "(null)" : String.valueOf(o);
    }
}