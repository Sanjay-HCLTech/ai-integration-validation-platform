package com.hcl.ai.insights;

import com.hcl.ai.llm.OpenAiService;
import com.hcl.ai.rca.RcaService;
import org.springframework.stereotype.Service;

@Service
public class InsightGenerator {

    private final RcaService rcaService;
    private final OpenAiService openAiService;

    public InsightGenerator(RcaService rcaService,
                            OpenAiService openAiService) {
        this.rcaService = rcaService;
        this.openAiService = openAiService;
    }

    public String generateInsight(Object result) {

        String rca = rcaService.analyze(result);

        String aiInsight = openAiService.analyzeLogs(rca);

        return rca + " | " + aiInsight;
    }
}
