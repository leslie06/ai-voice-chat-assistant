package com.vca.gateway.router;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.gateway.Candidate;
import com.vca.gateway.GatewayProperties;
import com.vca.gateway.registry.ProviderRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选排序: 把会话请求的厂商排在首位, 其余按配置顺序作为故障转移备选。
 * 若显式请求的厂商未注册(未启用), 只返回该厂商本身, 让调用侧明确报"未注册",
 * 避免用户手动切到某模型后被静默路由到其它厂商。
 */
public class VendorRouter {

    private final ProviderRegistry registry;
    private final GatewayProperties properties;

    public VendorRouter(ProviderRegistry registry, GatewayProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * @param requested 会话配置选定的主厂商(可为 null)
     * @return 有序候选列表(主厂商在前), 备选已过滤为"已注册"厂商; 可能为空
     */
    public List<Candidate> orderedCandidates(Capability capability, VendorType requested) {
        List<Candidate> configured = properties.candidates(capability);
        Map<VendorType, Candidate> ordered = new LinkedHashMap<>();

        // 1) 主厂商排首位(配置里有就用配置, 没有就给个默认候选)
        if (requested != null) {
            Candidate primary = configured.stream()
                    .filter(c -> c.vendor() == requested)
                    .findFirst()
                    .orElse(Candidate.of(requested));
            ordered.put(requested, primary);
            if (!registry.has(capability, requested)) {
                return new ArrayList<>(ordered.values());
            }
        }

        // 2) 其余已注册的配置候选作为备选
        for (Candidate c : configured) {
            if (registry.has(capability, c.vendor())) {
                ordered.putIfAbsent(c.vendor(), c);
            }
        }

        return new ArrayList<>(ordered.values());
    }
}
