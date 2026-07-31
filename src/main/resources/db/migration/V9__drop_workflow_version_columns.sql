alter table conversation_turn
    drop column workflow_version;

alter table agent_run
    drop column workflow_version;
