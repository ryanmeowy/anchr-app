alter table asset
    add column indexed_segment_count int not null default 0 after segment_count;
