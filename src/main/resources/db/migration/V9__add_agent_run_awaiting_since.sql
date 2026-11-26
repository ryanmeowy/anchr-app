alter table agent_run
    add column awaiting_since datetime(3) null
        comment '进入 AWAITING_TURN 状态的时间' after finished_at,
    add index idx_agent_run_awaiting (status, awaiting_since);

update agent_run
set awaiting_since = current_timestamp(3)
where status = 'AWAITING_TURN'
  and awaiting_since is null;
