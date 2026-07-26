package com.travel.agent.order;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.ArrayList;
import java.util.List;

/**
 * 行程冲突检查报告。
 * <p>由 {@link TravelOrderConflictTools#checkTravelOrderConflicts} 返回的 JSON 结构。</p>
 *
 * @author Hollis
 */
public class ConflictReport {

    /** 是否有任何冲突（severity 任意级别均算） */
    @JSONField(name = "has_conflict")
    private boolean hasConflict;

    /** 冲突总数 */
    @JSONField(name = "total_conflicts")
    private int totalConflicts;

    /** 冲突明细列表（按严重等级降序） */
    @JSONField(name = "conflicts")
    private List<ConflictItem> conflicts = new ArrayList<>();

    /** 一句话摘要，便于 LLM 在长对话中保留关键信息 */
    @JSONField(name = "summary")
    private String summary;

    public boolean isHasConflict() {
        return hasConflict;
    }

    public void setHasConflict(boolean hasConflict) {
        this.hasConflict = hasConflict;
    }

    public int getTotalConflicts() {
        return totalConflicts;
    }

    public void setTotalConflicts(int totalConflicts) {
        this.totalConflicts = totalConflicts;
    }

    public List<ConflictItem> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<ConflictItem> conflicts) {
        this.conflicts = conflicts;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
