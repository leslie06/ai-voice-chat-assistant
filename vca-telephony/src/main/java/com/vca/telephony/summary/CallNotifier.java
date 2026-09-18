package com.vca.telephony.summary;

import com.vca.orchestrator.call.CallSummary;

/**
 * 把通话小结推给商家。<b>推送失败不重要到要影响任何东西</b> —— 小结已经落库, 商家在后台还能看到,
 * 所以实现方自己吞掉异常、记一行日志即可。
 */
public interface CallNotifier {

    CallNotifier NOOP = summary -> {
    };

    void notify(CallSummary summary);
}
