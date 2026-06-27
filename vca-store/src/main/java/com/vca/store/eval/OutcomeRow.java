package com.vca.store.eval;

/** 按结局聚合的查询结果行(mapper 自动映射用; 列别名 cnt)。 */
public class OutcomeRow {

    private String outcome;
    private long cnt;

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public long getCnt() {
        return cnt;
    }

    public void setCnt(long cnt) {
        this.cnt = cnt;
    }
}
