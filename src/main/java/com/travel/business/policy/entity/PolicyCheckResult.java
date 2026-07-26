package com.travel.business.policy.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Hollis
 */
public class PolicyCheckResult {

    private boolean compliant;
    private List<String> violations = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();

    public static PolicyCheckResult compliant() {
        PolicyCheckResult result = new PolicyCheckResult();
        result.setCompliant(true);
        return result;
    }

    public boolean isCompliant() {
        return compliant;
    }

    public void setCompliant(boolean compliant) {
        this.compliant = compliant;
    }

    public List<String> getViolations() {
        return violations;
    }

    public void setViolations(List<String> violations) {
        this.violations = violations;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }
}
