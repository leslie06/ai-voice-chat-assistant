package com.vca.store.eval;

/** 按链路聚合的查询结果行(mapper 自动映射用; 列别名 total/errors/interrupts)。 */
public class ModeAggRow {

    private String mode;
    private long total;
    private long errors;
    private long interrupts;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getErrors() {
        return errors;
    }

    public void setErrors(long errors) {
        this.errors = errors;
    }

    public long getInterrupts() {
        return interrupts;
    }

    public void setInterrupts(long interrupts) {
        this.interrupts = interrupts;
    }
}
