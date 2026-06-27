package com.vca.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vca.store.entity.ConversationTurn;

/**
 * 对话存档 Mapper。继承 {@link BaseMapper} 即获得 insert/select 等通用 CRUD, 落库只用到 {@code insert}。
 */
public interface ConversationTurnMapper extends BaseMapper<ConversationTurn> {
}
