package com.gogo.travel.agent;

import com.gogo.travel.agent.tools.ReimbursementTools;
import io.agentscope.core.ReActAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * @author Hollis
 */
@Configuration("reimbursementAgentConfiguration")
public class ReimbursementAgent extends BaseSubAgent {

    @Autowired
    protected ReimbursementTools reimbursementTools;

    @Bean(name = "reimbursementAgent")
    @Scope("prototype")
    public ReActAgent build() {
        // todo 这里考虑用A2A接入远端agent。待实现
        return null;
    }
}
