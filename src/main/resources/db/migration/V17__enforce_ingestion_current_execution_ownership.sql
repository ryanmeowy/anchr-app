-- The nullable pointer breaks the item/execution insertion cycle: create the
-- item with NULL, create its execution, then atomically install the pointer.
-- The composite FK prevents a pointer from ever targeting another item's
-- execution.
alter table ingestion_task_item
    add constraint fk_ingestion_item_current_execution
        foreign key (current_execution_id, id)
        references ingestion_item_execution (id, item_id)
        on update restrict on delete restrict;
