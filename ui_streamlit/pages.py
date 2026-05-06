from __future__ import annotations

import base64
import html
import hashlib
import json
import os
import re
import time
import uuid
from typing import Any

import requests
import streamlit as st

REQUEST_TIMEOUT_SECONDS = 20
ACTION_STALE_TIMEOUT_SECONDS = 45
SUCCESS_CODE = 200
MAX_IMAGE_UPLOAD_BYTES = 10 * 1024 * 1024
BATCH_INGESTION_CONFIG: dict[str, dict[str, Any]] = {
    "image": {
        "label": "Image",
        "extensions": ["png", "jpg", "jpeg", "webp"],
        "batch_tasks_path": "/api/v1/image/batch-tasks",
    },
    "text": {
        "label": "Text",
        "extensions": ["pdf", "txt", "md", "markdown"],
        "batch_tasks_path": "/api/v1/ingestion/text-assets/batch-tasks",
    },
}
RESULT_STATE_KEYS = {
    "text": "result_state_text",
    "kb": "result_state_kb",
    "conversation": "result_state_conversation",
    "image": "result_state_image",
    "similar": "result_state_similar",
}
LAYOUT_OPTIONS = ["Large (2 per row)", "Compact (3 per row)"]
OCR_PREVIEW_MAX_CHARS = 100
SPO_PREVIEW_MAX_ITEMS = 3
SPO_PREVIEW_MAX_CHARS = 140


def render_text_search_page(base_url: str) -> None:
    st.subheader("Text Search")
    st.caption("Endpoint: POST /api/v1/vision/search-page")

    state = _get_result_state("text")
    if "next_cursor" not in state:
        state["next_cursor"] = ""
    if "has_more" not in state:
        state["has_more"] = False
    if "base_payload" not in state:
        state["base_payload"] = {}
    keyword_store_key = "text_search_keyword_store"
    keyword_widget_key = "text_search_keyword_widget"
    strategy_store_key = "text_search_strategy_store"
    strategy_widget_key = "text_search_strategy_widget"
    topk_store_key = "text_search_topk_store"
    topk_widget_key = "text_search_topk_widget"
    limit_store_key = "text_search_limit_store"
    limit_widget_key = "text_search_limit_widget"
    ocr_store_key = "text_search_enable_ocr_store"
    ocr_widget_key = "text_search_enable_ocr_widget"
    if keyword_store_key not in st.session_state:
        st.session_state[keyword_store_key] = ""
    if keyword_widget_key not in st.session_state:
        st.session_state[keyword_widget_key] = st.session_state[keyword_store_key]
    if strategy_store_key not in st.session_state:
        st.session_state[strategy_store_key] = "0"
    if strategy_widget_key not in st.session_state:
        st.session_state[strategy_widget_key] = st.session_state[strategy_store_key]
    if topk_store_key not in st.session_state:
        st.session_state[topk_store_key] = 20
    if topk_widget_key not in st.session_state:
        st.session_state[topk_widget_key] = int(st.session_state[topk_store_key])
    if limit_store_key not in st.session_state:
        st.session_state[limit_store_key] = 10
    if limit_widget_key not in st.session_state:
        st.session_state[limit_widget_key] = int(st.session_state[limit_store_key])
    if ocr_store_key not in st.session_state:
        st.session_state[ocr_store_key] = True
    if ocr_widget_key not in st.session_state:
        st.session_state[ocr_widget_key] = bool(st.session_state[ocr_store_key])

    with st.container(border=True):
        row1_col1, row1_col2 = st.columns([5, 2])
        keyword = row1_col1.text_input(
            "Keyword",
            placeholder="e.g. 猫咪在沙发上",
            key=keyword_widget_key,
        )
        strategy = row1_col2.selectbox(
            "Strategy",
            options=["0", "1", "2"],
            help="0: hybrid, 1: vector, 2: text",
            key=strategy_widget_key,
        )
        is_vector_strategy = str(strategy) == "1"
        is_text_strategy = str(strategy) == "2"
        if is_vector_strategy:
            synced_limit = int(st.session_state[topk_widget_key])
            st.session_state[limit_widget_key] = synced_limit
            st.session_state[limit_store_key] = synced_limit

        row2_col1, row2_col2 = st.columns([2, 2])
        top_k = row2_col1.number_input(
            "TopK",
            min_value=1,
            max_value=200,
            key=topk_widget_key,
            disabled=is_text_strategy,
        )
        limit = row2_col2.number_input(
            "Limit",
            min_value=1,
            max_value=100,
            key=limit_widget_key,
            disabled=is_vector_strategy,
        )
        effective_top_k = int(top_k)
        effective_limit = int(limit)
        if is_vector_strategy:
            effective_limit = effective_top_k
        if is_text_strategy:
            effective_top_k = None
        enable_ocr = st.checkbox("Enable OCR", key=ocr_widget_key)
        draft_payload = {
            "keyword": keyword.strip(),
            "searchType": str(strategy),
            "limit": int(effective_limit),
            "topK": effective_top_k,
            "enableOcr": bool(enable_ocr),
        }
        submitted = st.button(
            "Run Search",
            key="text_search_run_btn",
            type="primary",
            disabled=_is_action_busy("text_search_first", draft_payload),
        )
    if is_vector_strategy:
        st.caption("Vector strategy: Limit is fixed to TopK.")
    elif is_text_strategy:
        st.caption("Text strategy: TopK is disabled, only Limit is used.")
    st.session_state[keyword_store_key] = keyword
    st.session_state[strategy_store_key] = str(strategy)
    st.session_state[topk_store_key] = int(top_k)
    st.session_state[limit_store_key] = int(effective_limit)
    st.session_state[ocr_store_key] = bool(enable_ocr)

    if submitted:
        if not keyword.strip():
            st.warning("Keyword is required.")
            return

        first_payload = {
            "keyword": keyword.strip(),
            "searchType": str(strategy),
            "limit": int(effective_limit),
            "topK": effective_top_k,
            "enableOcr": bool(enable_ocr),
        }
        _queue_action("text_search_first", first_payload)
        st.rerun()

    if _consume_queued_action("text_search_first", draft_payload):
        try:
            with st.spinner("Searching..."):
                response_payload = _request_search_page(
                    url=f"{_normalize_base_url(base_url)}/api/v1/vision/search-page",
                    payload=draft_payload,
                )
            if response_payload is None:
                return
            data, headers, status_code = response_payload
            items = data.get("items") if isinstance(data, dict) else []
            next_cursor = _safe_str(data.get("nextCursor")) if isinstance(data, dict) else ""
            has_more = bool(data.get("hasMore")) if isinstance(data, dict) else False

            state["results"] = items if isinstance(items, list) else []
            state["headers"] = headers
            state["status_code"] = status_code
            state["payload_text"] = _pretty_json(draft_payload)
            state["keyword"] = draft_payload.get("keyword", "")
            state["base_payload"] = dict(draft_payload)
            state["next_cursor"] = next_cursor
            state["has_more"] = has_more and bool(next_cursor)
            state["has_searched"] = True
        finally:
            _complete_action("text_search_first")
            st.rerun()

    if state["has_searched"]:
        _render_response_meta(state["status_code"], state["headers"])
        if state["payload_text"]:
            st.code(state["payload_text"], language="json")
        requested_strategy = _safe_str(state.get("base_payload", {}).get("searchType")) if isinstance(
            state.get("base_payload"), dict
        ) else ""
        _render_search_results(
            state["results"],
            layout_key="text",
            requested_strategy=requested_strategy,
        )
        loaded_count = len(state["results"]) if isinstance(state.get("results"), list) else 0
        st.caption(f"Loaded {loaded_count} items")

        if state.get("has_more") and _safe_str(state.get("next_cursor")):
            more_payload = dict(state.get("base_payload", {}))
            more_payload["cursor"] = _safe_str(state.get("next_cursor"))
            if st.button(
                "Load More",
                key="text_search_load_more_btn",
                type="secondary",
                disabled=_is_action_busy("text_search_more", more_payload),
            ):
                _queue_action("text_search_more", more_payload)
                st.rerun()

            if _consume_queued_action("text_search_more", more_payload):
                try:
                    with st.spinner("Loading more..."):
                        response_payload = _request_search_page(
                            url=f"{_normalize_base_url(base_url)}/api/v1/vision/search-page",
                            payload=more_payload,
                        )
                    if response_payload is None:
                        return
                    data, headers, status_code = response_payload
                    items = data.get("items") if isinstance(data, dict) else []
                    next_cursor = _safe_str(data.get("nextCursor")) if isinstance(data, dict) else ""
                    has_more = bool(data.get("hasMore")) if isinstance(data, dict) else False

                    existing = state["results"] if isinstance(state.get("results"), list) else []
                    incoming = items if isinstance(items, list) else []
                    state["results"] = existing + incoming
                    state["headers"] = headers
                    state["status_code"] = status_code
                    state["payload_text"] = _pretty_json(more_payload)
                    state["next_cursor"] = next_cursor
                    state["has_more"] = has_more and bool(next_cursor)
                finally:
                    _complete_action("text_search_more")
                    st.rerun()


def render_kb_search_page(base_url: str) -> None:
    st.subheader("KB Search")
    st.caption("Endpoint: POST /api/v1/search/kb")

    state = _get_result_state("kb")
    query_store_key = "kb_search_query_store"
    query_widget_key = "kb_search_query_widget"
    topk_store_key = "kb_search_topk_store"
    topk_widget_key = "kb_search_topk_widget"
    limit_store_key = "kb_search_limit_store"
    limit_widget_key = "kb_search_limit_widget"

    if query_store_key not in st.session_state:
        st.session_state[query_store_key] = ""
    if query_widget_key not in st.session_state:
        st.session_state[query_widget_key] = st.session_state[query_store_key]
    if topk_store_key not in st.session_state:
        st.session_state[topk_store_key] = 20
    if topk_widget_key not in st.session_state:
        st.session_state[topk_widget_key] = int(st.session_state[topk_store_key])
    if limit_store_key not in st.session_state:
        st.session_state[limit_store_key] = 10
    if limit_widget_key not in st.session_state:
        st.session_state[limit_widget_key] = int(st.session_state[limit_store_key])

    with st.container(border=True):
        query = st.text_input(
            "Query",
            placeholder="e.g. innodb buffer pool",
            key=query_widget_key,
        )
        col1, col2 = st.columns(2)
        top_k = col1.number_input("TopK", min_value=1, max_value=200, key=topk_widget_key)
        limit = col2.number_input("Limit", min_value=1, max_value=200, key=limit_widget_key)
        payload = {
            "query": query.strip(),
            "topK": int(top_k),
            "limit": int(limit),
        }
        submitted = st.button(
            "Run KB Search",
            type="primary",
            key="kb_search_run_btn",
            disabled=_is_action_busy("kb_search_run", payload),
        )

    st.session_state[query_store_key] = query
    st.session_state[topk_store_key] = int(top_k)
    st.session_state[limit_store_key] = int(limit)

    if submitted:
        if not query.strip():
            st.warning("Query is required.")
            return
        _queue_action("kb_search_run", payload)
        st.rerun()

    if _consume_queued_action("kb_search_run", payload):
        try:
            with st.spinner("Searching KB..."):
                response_payload = _request_json(
                    method="POST",
                    url=f"{_normalize_base_url(base_url)}/api/v1/search/kb",
                    json=payload,
                )
            if response_payload is None:
                return
            data, headers, status_code = response_payload
            state["results"] = data
            state["headers"] = headers
            state["status_code"] = status_code
            state["payload_text"] = _pretty_json(payload)
            state["has_searched"] = True
        finally:
            _complete_action("kb_search_run")
            st.rerun()

    if state["has_searched"]:
        _render_response_meta(state["status_code"], state["headers"])
        if state["payload_text"]:
            st.code(state["payload_text"], language="json")
        _render_kb_search_results(state["results"])


def render_conversation_search_page(base_url: str) -> None:
    st.subheader("Conversation Search")
    st.caption("Endpoints: POST /api/conversations, POST /api/conversations/{sessionId}/messages, GET /api/conversations/{sessionId}/messages")

    state = _get_result_state("conversation")
    if "messages" not in state or not isinstance(state["messages"], list):
        state["messages"] = []
    if "mock_enabled" not in state:
        state["mock_enabled"] = False

    token_store_key = "conversation_token_store"
    token_widget_key = "conversation_token_widget"
    title_store_key = "conversation_title_store"
    title_widget_key = "conversation_title_widget"
    session_id_store_key = "conversation_session_id_store"
    session_id_widget_key = "conversation_session_id_widget"
    query_store_key = "conversation_query_store"
    query_widget_key = "conversation_query_widget"
    topk_store_key = "conversation_topk_store"
    topk_widget_key = "conversation_topk_widget"
    limit_store_key = "conversation_limit_store"
    limit_widget_key = "conversation_limit_widget"
    strategy_store_key = "conversation_strategy_store"
    strategy_widget_key = "conversation_strategy_widget"
    history_limit_store_key = "conversation_history_limit_store"
    history_limit_widget_key = "conversation_history_limit_widget"
    session_cache_key = "conversation_session_cache"

    if token_store_key not in st.session_state:
        st.session_state[token_store_key] = ""
    if token_widget_key not in st.session_state:
        st.session_state[token_widget_key] = st.session_state[token_store_key]
    if title_store_key not in st.session_state:
        st.session_state[title_store_key] = ""
    if title_widget_key not in st.session_state:
        st.session_state[title_widget_key] = st.session_state[title_store_key]
    if session_id_store_key not in st.session_state:
        st.session_state[session_id_store_key] = ""
    if session_id_widget_key not in st.session_state:
        st.session_state[session_id_widget_key] = st.session_state[session_id_store_key]
    if query_store_key not in st.session_state:
        st.session_state[query_store_key] = ""
    if query_widget_key not in st.session_state:
        st.session_state[query_widget_key] = st.session_state[query_store_key]
    if topk_store_key not in st.session_state:
        st.session_state[topk_store_key] = 60
    if topk_widget_key not in st.session_state:
        st.session_state[topk_widget_key] = int(st.session_state[topk_store_key])
    if limit_store_key not in st.session_state:
        st.session_state[limit_store_key] = 20
    if limit_widget_key not in st.session_state:
        st.session_state[limit_widget_key] = int(st.session_state[limit_store_key])
    if strategy_store_key not in st.session_state:
        st.session_state[strategy_store_key] = "KB_RRF_RERANK"
    if strategy_widget_key not in st.session_state:
        st.session_state[strategy_widget_key] = st.session_state[strategy_store_key]
    if history_limit_store_key not in st.session_state:
        st.session_state[history_limit_store_key] = 20
    if history_limit_widget_key not in st.session_state:
        st.session_state[history_limit_widget_key] = int(st.session_state[history_limit_store_key])
    if session_cache_key not in st.session_state or not isinstance(st.session_state[session_cache_key], dict):
        st.session_state[session_cache_key] = {}

    session_cache: dict[str, dict[str, Any]] = st.session_state[session_cache_key]
    known_session_ids = list(session_cache.keys())

    with st.container(border=True):
        state["mock_enabled"] = st.toggle(
            "Mock conversation preview (no backend)",
            value=bool(state.get("mock_enabled", False)),
            key="conversation_mock_enabled_toggle",
        )
        if state["mock_enabled"]:
            preview_col1, preview_col2, preview_col3 = st.columns([1.1, 1.1, 1.1])
            if preview_col1.button("Load Mock Conversation", type="secondary", key="conversation_mock_load_btn"):
                state["messages"] = _build_conversation_mock_messages()
                state["has_searched"] = True
                state["status_code"] = 200
                state["headers"] = {}
                state["payload_text"] = _pretty_json({"mock": True, "action": "load_conversation"})
                st.rerun()
            if preview_col2.button("Append Mock Turn", key="conversation_mock_append_btn"):
                state["messages"] = _append_conversation_mock_turn(state.get("messages", []))
                state["has_searched"] = True
                state["status_code"] = 200
                state["headers"] = {}
                state["payload_text"] = _pretty_json({"mock": True, "action": "append_turn"})
                st.rerun()
            if preview_col3.button("Clear Mock Conversation", key="conversation_mock_clear_btn"):
                state["messages"] = []
                state["has_searched"] = False
                state["payload_text"] = ""
                st.rerun()

        token = st.text_input("X-Access-Token", type="password", key=token_widget_key)
        title_col, create_col = st.columns([4, 1.2])
        new_title = title_col.text_input(
            "New Session Title (optional)",
            placeholder="e.g. MySQL 架构问答",
            key=title_widget_key,
        )
        create_payload = {"title": new_title.strip(), "hasToken": bool(token.strip())}
        create_clicked = create_col.button(
            "Create Session",
            type="primary",
            disabled=_is_action_busy("conversation_create", create_payload),
        )

    selected_session_id = ""
    if known_session_ids:
        selected_session_id = st.selectbox(
            "Known Sessions",
            options=[""] + known_session_ids,
            format_func=lambda sid: _format_conversation_session_option(sid, session_cache),
            key="conversation_known_session_select",
        )
        if selected_session_id:
            st.session_state[session_id_widget_key] = selected_session_id

    session_id = st.text_input(
        "Session ID",
        placeholder="paste session id here",
        key=session_id_widget_key,
    ).strip()

    with st.container(border=True):
        col1, col2, col3 = st.columns([2, 2, 2])
        top_k = col1.number_input("TopK", min_value=1, max_value=200, key=topk_widget_key)
        limit = col2.number_input("Limit", min_value=1, max_value=200, key=limit_widget_key)
        strategy = col3.selectbox(
            "Strategy",
            options=["KB_RRF_RERANK", "KB_RRF"],
            key=strategy_widget_key,
        )
        history_limit = st.number_input("History Limit", min_value=1, max_value=100, key=history_limit_widget_key)
        query = st.text_area(
            "Message",
            placeholder="e.g. 那 InnoDB 呢",
            height=110,
            key=query_widget_key,
        )
        load_payload = {"sessionId": session_id, "limit": int(history_limit), "hasToken": bool(token.strip())}
        send_payload = {
            "sessionId": session_id,
            "query": query.strip(),
            "topK": int(top_k),
            "limit": int(limit),
            "strategy": strategy,
            "hasToken": bool(token.strip()),
        }
        action_col1, action_col2 = st.columns([1.2, 1.2])
        load_clicked = action_col1.button(
            "Load Messages",
            disabled=_is_action_busy("conversation_load_messages", load_payload),
        )
        send_clicked = action_col2.button(
            "Send Message",
            type="primary",
            disabled=_is_action_busy("conversation_send_message", send_payload),
        )

    st.session_state[token_store_key] = token
    st.session_state[title_store_key] = new_title
    st.session_state[session_id_store_key] = session_id
    st.session_state[query_store_key] = query
    st.session_state[topk_store_key] = int(top_k)
    st.session_state[limit_store_key] = int(limit)
    st.session_state[strategy_store_key] = strategy
    st.session_state[history_limit_store_key] = int(history_limit)

    if create_clicked:
        if not token.strip():
            st.warning("X-Access-Token is required.")
            return
        _queue_action("conversation_create", create_payload)
        st.rerun()
    if load_clicked:
        if not token.strip():
            st.warning("X-Access-Token is required.")
            return
        if not session_id:
            st.warning("Session ID is required.")
            return
        _queue_action("conversation_load_messages", load_payload)
        st.rerun()
    if send_clicked:
        if not token.strip():
            st.warning("X-Access-Token is required.")
            return
        if not session_id:
            st.warning("Session ID is required.")
            return
        if not query.strip():
            st.warning("Message is required.")
            return
        _queue_action("conversation_send_message", send_payload)
        st.rerun()

    base = _normalize_base_url(base_url)
    auth_headers = {"X-Access-Token": token.strip()} if token.strip() else {}

    if _consume_queued_action("conversation_create", create_payload):
        try:
            with st.spinner("Creating session..."):
                response_payload = _request_object_json(
                    method="POST",
                    url=f"{base}/api/conversations",
                    json={"title": new_title.strip()} if new_title.strip() else {},
                    headers=auth_headers,
                )
            if response_payload is None:
                return
            data, headers, status_code = response_payload
            if not isinstance(data, dict):
                st.error("Unexpected response: session data is not an object.")
                return
            created_session_id = _safe_str(data.get("sessionId"))
            if created_session_id:
                session_cache[created_session_id] = data
                st.session_state[session_id_widget_key] = created_session_id
                st.session_state[session_id_store_key] = created_session_id
                state["messages"] = []
            state["results"] = data
            state["headers"] = headers
            state["status_code"] = status_code
            state["payload_text"] = _pretty_json({"title": new_title.strip()})
            state["has_searched"] = True
        finally:
            _complete_action("conversation_create")
            st.rerun()

    if _consume_queued_action("conversation_load_messages", load_payload):
        try:
            with st.spinner("Loading messages..."):
                response_payload = _request_object_json(
                    method="GET",
                    url=f"{base}/api/conversations/{session_id}/messages",
                    params={"limit": int(history_limit)},
                    headers=auth_headers,
                )
            if response_payload is None:
                return
            data, headers, status_code = response_payload
            turns = data.get("turns") if isinstance(data, dict) else []
            state["messages"] = turns if isinstance(turns, list) else []
            state["results"] = data if isinstance(data, dict) else {}
            state["headers"] = headers
            state["status_code"] = status_code
            state["payload_text"] = _pretty_json({"sessionId": session_id, "limit": int(history_limit)})
            state["has_searched"] = True
        finally:
            _complete_action("conversation_load_messages")
            st.rerun()

    if _consume_queued_action("conversation_send_message", send_payload):
        try:
            with st.spinner("Sending message..."):
                response_payload = _request_object_json(
                    method="POST",
                    url=f"{base}/api/conversations/{session_id}/messages",
                    json={
                        "query": query.strip(),
                        "topK": int(top_k),
                        "limit": int(limit),
                        "strategy": strategy,
                    },
                    headers=auth_headers,
                )
            if response_payload is None:
                return
            data, headers, status_code = response_payload
            state["results"] = data if isinstance(data, dict) else {}
            state["headers"] = headers
            state["status_code"] = status_code
            state["payload_text"] = _pretty_json(
                {
                    "sessionId": session_id,
                    "query": query.strip(),
                    "topK": int(top_k),
                    "limit": int(limit),
                    "strategy": strategy,
                }
            )
            state["has_searched"] = True
            st.session_state[query_widget_key] = ""
            st.session_state[query_store_key] = ""
            with st.spinner("Refreshing messages..."):
                history_response = _request_object_json(
                    method="GET",
                    url=f"{base}/api/conversations/{session_id}/messages",
                    params={"limit": int(history_limit)},
                    headers=auth_headers,
                )
            if history_response is not None:
                history_data, _, _ = history_response
                turns = history_data.get("turns") if isinstance(history_data, dict) else []
                state["messages"] = turns if isinstance(turns, list) else []
        finally:
            _complete_action("conversation_send_message")
            st.rerun()

    if state.get("has_searched"):
        _render_response_meta(state.get("status_code", 200), state.get("headers", {}))
        if state.get("payload_text"):
            st.code(state["payload_text"], language="json")
        _render_conversation_latest_trace(state.get("results"))

    if session_id:
        st.caption(f"Current Session: {session_id}")
    _render_conversation_messages(state.get("messages", []))


def _format_conversation_session_option(session_id: str, cache: dict[str, dict[str, Any]]) -> str:
    if not session_id:
        return ""
    session = cache.get(session_id)
    if not isinstance(session, dict):
        return session_id
    title = _safe_str(session.get("title"))
    if title:
        return f"{title} ({session_id})"
    return session_id


def _render_conversation_messages(messages: Any) -> None:
    st.markdown("### Conversation Messages")
    _inject_conversation_chat_styles()
    if not isinstance(messages, list) or not messages:
        st.info("No messages loaded.")
        return
    normalized = [item for item in messages if isinstance(item, dict)]
    if not normalized:
        st.info("No messages loaded.")
        return
    for idx, message in enumerate(normalized, start=1):
        query = _safe_str(message.get("query"))
        rewritten_query = _safe_str(message.get("rewrittenQuery"))
        answer = _safe_str(message.get("answer"))
        created_at = message.get("createdAt")
        turn_id = _safe_str(message.get("turnId"))
        citations = message.get("citations")
        retrieval_trace = message.get("retrievalTrace")
        suggested_questions = message.get("suggestedQuestions")
        st.markdown(
            f"<div class='conversation-turn-sep'>Turn {idx}"
            f"{' · ' + turn_id if turn_id else ''}"
            f"{' · ' + str(created_at) if isinstance(created_at, int) else ''}</div>",
            unsafe_allow_html=True,
        )
        with st.chat_message("user"):
            st.markdown(query or "-")
            if rewritten_query and rewritten_query != query:
                st.caption(f"Rewritten: {rewritten_query}")
        with st.chat_message("assistant"):
            st.markdown(answer or "-")
            _render_conversation_citations(citations)
            _render_conversation_retrieval_trace(retrieval_trace, compact=True)
            _render_conversation_suggested_questions(suggested_questions)


def _render_conversation_latest_trace(results: Any) -> None:
    if not isinstance(results, dict):
        return
    trace = results.get("retrievalTrace")
    if not isinstance(trace, dict):
        return
    st.markdown("### Latest Retrieval Trace")
    _render_conversation_retrieval_trace(trace, compact=False)


def _render_conversation_retrieval_trace(trace: Any, compact: bool) -> None:
    if not isinstance(trace, dict):
        return
    top_k = trace.get("topK")
    limit = trace.get("limit")
    strategy = _safe_str(trace.get("strategy"))
    strategy_effective = _safe_str(trace.get("strategyEffective"))
    rewrite_reason = _safe_str(trace.get("rewriteReason"))
    rewrite_confidence = trace.get("rewriteConfidence")
    rewrite_fallback = trace.get("rewriteFallback")
    retrieved_count = trace.get("retrievedCount")
    grouped_counts = trace.get("groupedResultCounts")
    top_segment_ids = trace.get("topSegmentIds")
    top_hit_sources = trace.get("topHitSources")
    answer_fallback = trace.get("answerFallback")
    answer_fallback_reason = _safe_str(trace.get("answerFallbackReason"))

    if compact:
        st.caption(
            "Trace: "
            f"topK={top_k if isinstance(top_k, int) else '-'}, "
            f"limit={limit if isinstance(limit, int) else '-'}, "
            f"strategy={strategy or '-'}"
        )
    else:
        st.caption(
            "TopK Summary: "
            f"topK={top_k if isinstance(top_k, int) else '-'}, "
            f"limit={limit if isinstance(limit, int) else '-'}, "
            f"strategy={strategy or '-'}, "
            f"effective={strategy_effective or '-'}, "
            f"retrieved={retrieved_count if isinstance(retrieved_count, int) else '-'}"
        )

    if rewrite_reason:
        rewrite_caption = f"Rewrite Reason: {rewrite_reason}"
        if isinstance(rewrite_confidence, (int, float)):
            rewrite_caption += f" | confidence={rewrite_confidence:.2f}"
        if isinstance(rewrite_fallback, bool):
            rewrite_caption += f" | fallback={str(rewrite_fallback).lower()}"
        st.caption(rewrite_caption)

    if isinstance(grouped_counts, dict) and grouped_counts:
        grouped_text = ", ".join(
            f"{_safe_str(group_key) or '-'}={count if isinstance(count, int) else '-'}"
            for group_key, count in grouped_counts.items()
        )
        st.caption(f"Grouped: {grouped_text}")

    if isinstance(top_hit_sources, list) and top_hit_sources:
        sources = [source for source in (_safe_str(item) for item in top_hit_sources) if source]
        if sources:
            st.caption(f"Explain Hit Sources: {', '.join(sources)}")

    if isinstance(top_segment_ids, list) and top_segment_ids:
        segments = [segment for segment in (_safe_str(item) for item in top_segment_ids) if segment]
        if segments:
            st.caption(f"Top Segment IDs: {', '.join(segments)}")

    if isinstance(answer_fallback, bool):
        fallback_text = f"Answer Fallback: {str(answer_fallback).lower()}"
        if answer_fallback_reason:
            fallback_text += f" ({answer_fallback_reason})"
        st.caption(fallback_text)


def _render_conversation_suggested_questions(suggested_questions: Any) -> None:
    if not isinstance(suggested_questions, list) or not suggested_questions:
        return
    normalized = [item for item in (_safe_str(question) for question in suggested_questions) if item]
    if not normalized:
        return
    st.markdown("**Suggested Questions**")
    for idx, question in enumerate(normalized, start=1):
        st.caption(f"{idx}. {question}")


def _render_conversation_citations(citations: Any) -> None:
    st.markdown("**Citations**")
    if not isinstance(citations, list) or not citations:
        st.caption("No citations.")
        return
    for idx, citation in enumerate(citations, start=1):
        if not isinstance(citation, dict):
            continue
        file_name = _safe_str(citation.get("fileName")) or "-"
        snippet = _safe_str(citation.get("snippet")) or "-"
        page_no = citation.get("pageNo")
        hit_type = _safe_str(citation.get("hitType")) or "-"
        asset_id = _safe_str(citation.get("assetId")) or "-"
        segment_id = _safe_str(citation.get("segmentId")) or "-"
        page_text = str(page_no) if isinstance(page_no, int) else "-"
        with st.container(border=True):
            st.caption(f"[{idx}] file={file_name} page={page_text} hitType={hit_type}")
            st.write(snippet)
            st.caption(f"assetId={asset_id} | segmentId={segment_id}")


def _build_conversation_mock_messages() -> list[dict[str, Any]]:
    now_ms = int(time.time() * 1000)
    return [
        {
            "turnId": "turn_mock_0001",
            "sessionId": "cvs_mock_demo",
            "query": "mysql 架构是什么",
            "rewrittenQuery": "mysql 架构 核心组件 关系",
            "answer": "MySQL 架构通常包含连接层、SQL 层和存储引擎层。[1]",
            "retrievalTrace": {
                "topK": 60,
                "limit": 20,
                "strategy": "KB_RRF_RERANK",
                "strategyEffective": "KB_RRF_RERANK",
                "rewriteReason": "rewrite_by_model",
                "rewriteConfidence": 0.92,
                "rewriteFallback": False,
                "retrievedCount": 2,
                "groupedResultCounts": {"TEXT": 1, "IMAGE": 1},
                "topSegmentIds": ["seg_mock_001", "seg_mock_002"],
                "topHitSources": ["VECTOR", "CONTENT", "TAG"],
                "answerFallback": False,
                "answerFallbackReason": None,
            },
            "suggestedQuestions": [
                "《mysql-notes.pdf》里还有哪些和“mysql”直接相关的内容？",
                "有没有“mysql”对应的结构图或示意图可对照理解？",
            ],
            "citations": [
                {
                    "fileName": "mysql-notes.pdf",
                    "pageNo": 3,
                    "snippet": "MySQL architecture consists of connection layer, SQL layer and storage engines.",
                    "hitType": "TEXT_CHUNK",
                    "assetId": "asset_mock_001",
                    "segmentId": "seg_mock_001",
                }
            ],
            "createdAt": now_ms - 120000,
        },
        {
            "turnId": "turn_mock_0002",
            "sessionId": "cvs_mock_demo",
            "query": "那 InnoDB 呢",
            "rewrittenQuery": "mysql 架构 InnoDB 作用 事务 行锁",
            "answer": "InnoDB 是默认事务型存储引擎，支持事务、行级锁和崩溃恢复。[1][2]",
            "retrievalTrace": {
                "topK": 60,
                "limit": 20,
                "strategy": "KB_RRF_RERANK",
                "strategyEffective": "KB_RRF_RERANK",
                "rewriteReason": "rewrite_by_model",
                "rewriteConfidence": 0.95,
                "rewriteFallback": False,
                "retrievedCount": 3,
                "groupedResultCounts": {"TEXT": 2, "IMAGE": 1},
                "topSegmentIds": ["seg_mock_010", "seg_mock_020", "seg_mock_021"],
                "topHitSources": ["VECTOR", "CONTENT", "CAPTION"],
                "answerFallback": False,
                "answerFallbackReason": None,
            },
            "suggestedQuestions": [
                "在《mysql-engine-guide.md》第12页，关于“InnoDB”还有哪些关键点？",
                "“InnoDB”和“buffer pool”之间的关系是什么？",
            ],
            "citations": [
                {
                    "fileName": "mysql-engine-guide.md",
                    "pageNo": 12,
                    "snippet": "InnoDB supports ACID transactions and row-level locking.",
                    "hitType": "TEXT_CHUNK",
                    "assetId": "asset_mock_002",
                    "segmentId": "seg_mock_010",
                },
                {
                    "fileName": "innodb-arch.png",
                    "pageNo": None,
                    "snippet": "InnoDB 内部包含 buffer pool、redo log、undo log 等核心模块。",
                    "hitType": "CAPTION",
                    "assetId": "asset_mock_003",
                    "segmentId": "seg_mock_020",
                },
            ],
            "createdAt": now_ms - 30000,
        },
    ]


def _append_conversation_mock_turn(messages: Any) -> list[dict[str, Any]]:
    normalized = [item for item in messages if isinstance(item, dict)] if isinstance(messages, list) else []
    if not normalized:
        return _build_conversation_mock_messages()
    now_ms = int(time.time() * 1000)
    next_index = len(normalized) + 1
    next_turn = {
        "turnId": f"turn_mock_{next_index:04d}",
        "sessionId": _safe_str(normalized[-1].get("sessionId")) or "cvs_mock_demo",
        "query": "补充说明下索引和检索链路",
        "rewrittenQuery": "mysql 检索链路 向量检索 召回 排序",
        "answer": "检索链路可拆成召回、重排和答案生成三个阶段，引用证据用于可追溯性。[1]",
        "retrievalTrace": {
            "topK": 60,
            "limit": 20,
            "strategy": "KB_RRF_RERANK",
            "strategyEffective": "KB_RRF_RERANK",
            "rewriteReason": "rewrite_by_model",
            "rewriteConfidence": 0.90,
            "rewriteFallback": False,
            "retrievedCount": 1,
            "groupedResultCounts": {"TEXT": 1},
            "topSegmentIds": ["seg_mock_030"],
            "topHitSources": ["VECTOR", "CONTENT"],
            "answerFallback": False,
            "answerFallbackReason": None,
        },
        "suggestedQuestions": [
            "《retrieval-pipeline.md》里还有哪些和“检索链路”直接相关的内容？",
            "“向量检索”和“重排”之间的关系是什么？",
        ],
        "citations": [
            {
                "fileName": "retrieval-pipeline.md",
                "pageNo": 5,
                "snippet": "Typical retrieval pipeline: recall, rerank, generation.",
                "hitType": "TEXT_CHUNK",
                "assetId": "asset_mock_004",
                "segmentId": "seg_mock_030",
            }
        ],
        "createdAt": now_ms,
    }
    return normalized + [next_turn]


def _inject_conversation_chat_styles() -> None:
    st.markdown(
        """
        <style>
        .conversation-turn-sep {
          margin-top: 10px;
          margin-bottom: 6px;
          font-size: 12px;
          color: #6b7c93;
          font-weight: 600;
          letter-spacing: 0.01em;
        }
        </style>
        """,
        unsafe_allow_html=True,
    )


def render_image_search_page(base_url: str) -> None:
    st.subheader("Search By Image")
    st.caption("Endpoint: POST /api/v1/vision/search-by-image")
    st.caption("Image limit: up to 10MB per file. Backend will apply compression for embedding when needed.")

    state = _get_result_state("image")
    limit_store_key = "image_search_limit_store"
    limit_widget_key = "image_search_limit_widget"
    if limit_store_key not in st.session_state:
        st.session_state[limit_store_key] = 20
    if limit_widget_key not in st.session_state:
        st.session_state[limit_widget_key] = int(st.session_state[limit_store_key])

    uploaded = st.file_uploader(
        "Upload image",
        type=["png", "jpg", "jpeg", "webp"],
        accept_multiple_files=False,
        key="image_search_upload_widget",
    )
    limit = st.number_input("Limit", min_value=1, max_value=100, key=limit_widget_key)
    draft_payload = {
        "limit": int(limit),
        "file": _uploaded_file_fingerprint(uploaded),
    }
    submitted = st.button(
        "Run Image Search",
        type="primary",
        disabled=_is_action_busy("image_search", draft_payload),
    )
    st.session_state[limit_store_key] = int(limit)

    if submitted:
        if uploaded is None:
            st.warning("Please upload an image file first.")
            return
        if not _validate_uploaded_image_size(uploaded, "Uploaded image"):
            return
        _queue_action("image_search", draft_payload)
        st.rerun()

    if _consume_queued_action("image_search", draft_payload):
        try:
            with st.spinner("Searching by image..."):
                response_payload = _request_json(
                    method="POST",
                    url=f"{_normalize_base_url(base_url)}/api/v1/vision/search-by-image",
                    params={"limit": int(limit)},
                    files={"file": (uploaded.name, uploaded.getvalue(), uploaded.type or "application/octet-stream")},
                )
            if response_payload is None:
                return
            data, headers, status_code = response_payload

            state["results"] = data
            state["headers"] = headers
            state["status_code"] = status_code
            state["payload_text"] = (
                f"POST {_normalize_base_url(base_url)}/api/v1/vision/search-by-image?limit={int(limit)}"
            )
            state["has_searched"] = True
        finally:
            _complete_action("image_search")
            st.rerun()

    if state["has_searched"]:
        _render_response_meta(state["status_code"], state["headers"])
        if state["payload_text"]:
            st.code(state["payload_text"], language="bash")
        _render_search_results(state["results"], layout_key="image")


def _render_kb_search_results(results: list[Any]) -> None:
    st.markdown("### KB Results")
    if not results:
        st.info("No results found.")
        return
    normalized = [item for item in results if isinstance(item, dict)]
    if not normalized:
        st.error("Unexpected response shape: result items are not objects.")
        return

    def _format_anchor(anchor_payload: Any) -> str:
        if not isinstance(anchor_payload, dict):
            return "-"
        parts: list[str] = []
        page_no = anchor_payload.get("pageNo")
        chunk_order = anchor_payload.get("chunkOrder")
        bbox = anchor_payload.get("bbox")
        if isinstance(page_no, int):
            parts.append(f"pageNo={page_no}")
        if isinstance(chunk_order, int):
            parts.append(f"chunkOrder={chunk_order}")
        if isinstance(bbox, list) and bbox:
            parts.append(f"bbox={bbox}")
        elif isinstance(bbox, dict) and bbox:
            parts.append(f"bbox={bbox}")
        return ", ".join(parts) if parts else "-"

    for idx, item in enumerate(normalized, start=1):
        result_type = _safe_str(item.get("resultType")) or "-"
        asset_type = _safe_str(item.get("assetType")) or "-"
        snippet = _safe_str(item.get("snippet")) or "-"
        score = item.get("score")
        source_ref = _safe_str(item.get("sourceRef"))
        segment_id = _safe_str(item.get("segmentId"))
        asset_id = _safe_str(item.get("assetId"))
        thumbnail = _safe_str(item.get("thumbnail"))
        ocr_summary = _safe_str(item.get("ocrSummary"))
        total_hits = int(item.get("totalHits")) if isinstance(item.get("totalHits"), int) else 1
        explain = item.get("explain") if isinstance(item.get("explain"), dict) else {}
        hit_sources = explain.get("hitSources") if isinstance(explain.get("hitSources"), list) else []
        hit_sources_text = ", ".join(_safe_str(source).upper() for source in hit_sources if _safe_str(source))

        top_chunks = item.get("topChunks") if isinstance(item.get("topChunks"), list) else []
        top_chunk_items = [chunk for chunk in top_chunks if isinstance(chunk, dict)]
        if not top_chunk_items:
            top_chunk_items = [
                {
                    "segmentId": segment_id,
                    "segmentType": result_type,
                    "snippet": snippet,
                    "score": score,
                    "pageNo": item.get("pageNo"),
                    "anchor": item.get("anchor"),
                    "sourceRef": source_ref,
                    "thumbnail": thumbnail,
                    "ocrSummary": ocr_summary,
                }
            ]

        with st.container(border=True):
            left, right = st.columns([5, 1])
            left.markdown(f"**{idx}. {asset_type} · {result_type}**")
            right.markdown(f"**{float(score):.4f}**" if isinstance(score, (int, float)) else "**-**")

            summary_cols = st.columns([2, 3]) if asset_type.upper() == "IMAGE" and thumbnail else st.columns([1, 4])
            if asset_type.upper() == "IMAGE" and thumbnail:
                summary_cols[0].image(thumbnail, width=220)
                summary_cols[1].write(ocr_summary or snippet or "-")
            else:
                summary_cols[1].write(snippet or "-")

            st.caption(f"totalHits: {max(total_hits, len(top_chunk_items))}")
            if hit_sources_text:
                st.caption(f"hitSources: {hit_sources_text}")
            id_parts = []
            if segment_id:
                id_parts.append(f"segmentId={segment_id}")
            if asset_id:
                id_parts.append(f"assetId={asset_id}")
            if id_parts:
                st.caption(" | ".join(id_parts))
            if source_ref:
                st.caption(f"sourceRef: {source_ref}")

            with st.expander(f"Top Chunks ({len(top_chunk_items)})"):
                for chunk_idx, chunk in enumerate(top_chunk_items, start=1):
                    chunk_score = chunk.get("score")
                    chunk_segment_id = _safe_str(chunk.get("segmentId")) or "-"
                    chunk_segment_type = _safe_str(chunk.get("segmentType")) or "-"
                    chunk_snippet = _safe_str(chunk.get("snippet")) or "-"
                    chunk_anchor_text = _format_anchor(chunk.get("anchor"))
                    chunk_source_ref = _safe_str(chunk.get("sourceRef"))
                    chunk_header = (
                        f"{chunk_idx}. {chunk_segment_type} · {chunk_segment_id}"
                        f" · score={float(chunk_score):.4f}"
                    ) if isinstance(chunk_score, (int, float)) else f"{chunk_idx}. {chunk_segment_type} · {chunk_segment_id}"
                    st.markdown(chunk_header)
                    st.write(chunk_snippet)
                    st.caption(f"anchor: {chunk_anchor_text}")
                    if chunk_source_ref:
                        st.caption(f"sourceRef: {chunk_source_ref}")


def render_image_analyze_page(base_url: str) -> None:
    st.subheader("Image Analyze")
    st.caption("Endpoint: POST /api/v1/vision/analyze/stream")
    st.info("Upload one image and stream full analysis: summary, tags, OCR, graph, and search suggestions.")
    st.caption("Image limit: up to 10MB per file. Backend will apply compression for embedding when needed.")

    analyze_state_key = "image_analyze_state"
    enable_ocr_store_key = "image_analyze_enable_ocr_store"
    enable_ocr_widget_key = "image_analyze_enable_ocr_widget"
    enable_graph_store_key = "image_analyze_enable_graph_store"
    enable_graph_widget_key = "image_analyze_enable_graph_widget"
    if enable_ocr_store_key not in st.session_state:
        st.session_state[enable_ocr_store_key] = True
    if enable_ocr_widget_key not in st.session_state:
        st.session_state[enable_ocr_widget_key] = bool(st.session_state[enable_ocr_store_key])
    if enable_graph_store_key not in st.session_state:
        st.session_state[enable_graph_store_key] = True
    if enable_graph_widget_key not in st.session_state:
        st.session_state[enable_graph_widget_key] = bool(st.session_state[enable_graph_store_key])
    if analyze_state_key not in st.session_state or not isinstance(st.session_state[analyze_state_key], dict):
        st.session_state[analyze_state_key] = {
            "has_result": False,
            "image_name": "",
            "image_bytes": None,
            "meta": {},
            "summary": "",
            "tags": [],
            "ocr": "",
            "graph": [],
            "suggestions": [],
            "status": "",
        }
    analyze_state = st.session_state[analyze_state_key]

    uploaded = st.file_uploader(
        "Upload image",
        type=["png", "jpg", "jpeg", "webp"],
        accept_multiple_files=False,
        key="image_analyze_upload_widget",
    )
    col1, col2 = st.columns(2)
    enable_ocr = col1.checkbox("Enable OCR", key=enable_ocr_widget_key)
    enable_graph = col2.checkbox("Enable Graph (SPO)", key=enable_graph_widget_key)
    analyze_payload = {
        "file": _uploaded_file_fingerprint(uploaded),
        "enableOcr": bool(enable_ocr),
        "enableGraph": bool(enable_graph),
    }
    submitted = st.button(
        "Run Analyze",
        type="primary",
        disabled=_is_action_busy("image_analyze", analyze_payload),
    )
    st.session_state[enable_ocr_store_key] = bool(enable_ocr)
    st.session_state[enable_graph_store_key] = bool(enable_graph)

    if submitted:
        if uploaded is None:
            st.warning("Please upload an image file first.")
            return
        if not _validate_uploaded_image_size(uploaded, "Uploaded image"):
            return
        _queue_action("image_analyze", analyze_payload)
        st.rerun()

    if _consume_queued_action("image_analyze", analyze_payload):
        if uploaded is None:
            _complete_action("image_analyze")
            st.rerun()
            return
        try:
            st.markdown("### Uploaded Image")
            st.image(uploaded.getvalue(), caption=uploaded.name, width=320)

            st.markdown("### Streaming Analysis")
            st.markdown("**Meta**")
            meta_box = st.empty()
            st.markdown("**Summary**")
            summary_box = st.empty()
            st.markdown("**Tags**")
            tags_box = st.empty()
            st.markdown("**OCR Text**")
            ocr_box = st.empty()
            st.markdown("**Graph Triples**")
            graph_box = st.empty()
            st.markdown("**Search Suggestions**")
            suggestion_box = st.empty()
            status_box = st.empty()

            summary_text = ""
            ocr_text = ""
            tags: list[str] = []
            graph_items: list[dict[str, Any]] = []
            suggestions: list[str] = []
            meta_payload: dict[str, Any] = {}
            final_status = ""

            try:
                response = requests.post(
                    f"{_normalize_base_url(base_url)}/api/v1/vision/analyze/stream",
                    files={"file": (uploaded.name, uploaded.getvalue(), uploaded.type or "application/octet-stream")},
                    data={
                        "mode": "general",
                        "enableOcr": str(bool(enable_ocr)).lower(),
                        "enableGraph": str(bool(enable_graph)).lower(),
                    },
                    stream=True,
                    timeout=(5, 180),
                )
            except requests.exceptions.RequestException as exc:
                st.error(f"Failed to call /vision/analyze/stream: {exc}")
                return

            if response.status_code >= 400:
                st.error(f"HTTP {response.status_code}: {response.text[:600]}")
                return

            response.encoding = "utf-8"

            for event_name, payload in _iter_sse_events(response):
                if event_name == "meta":
                    if isinstance(payload, dict):
                        meta_payload = payload
                    meta_box.json(payload)
                    continue
                if event_name == "summary":
                    delta = _safe_str(payload.get("delta")) if isinstance(payload, dict) else ""
                    summary_text = _render_typewriter_delta(summary_box, summary_text, delta)
                    continue
                if event_name == "summary_end":
                    final_text = _safe_str(payload.get("text")) if isinstance(payload, dict) else ""
                    if final_text:
                        summary_text = final_text
                    summary_box.write(summary_text if summary_text else "-")
                    continue
                if event_name == "tags":
                    items = payload.get("items") if isinstance(payload, dict) else None
                    if isinstance(items, list):
                        tags = [str(x).strip() for x in items if str(x).strip()]
                    tags_box.write(tags if tags else [])
                    continue
                if event_name == "ocr":
                    delta = _safe_str(payload.get("delta")) if isinstance(payload, dict) else ""
                    ocr_text += delta
                    ocr_box.code(ocr_text if ocr_text else "-", language="text")
                    continue
                if event_name == "ocr_end":
                    final_ocr = _safe_str(payload.get("text")) if isinstance(payload, dict) else ""
                    if final_ocr:
                        ocr_text = final_ocr
                    ocr_box.code(ocr_text if ocr_text else "-", language="text")
                    continue
                if event_name == "graph":
                    items = payload.get("items") if isinstance(payload, dict) else None
                    if isinstance(items, list):
                        graph_items = [x for x in items if isinstance(x, dict)]
                    if graph_items:
                        graph_box.dataframe(graph_items, use_container_width=True)
                    else:
                        graph_box.info("No graph triples.")
                    continue
                if event_name == "suggestions":
                    items = payload.get("items") if isinstance(payload, dict) else None
                    if isinstance(items, list):
                        suggestions = [str(x).strip() for x in items if str(x).strip()]
                    suggestion_box.write(suggestions if suggestions else [])
                    continue
                if event_name == "error":
                    message = _safe_str(payload.get("message")) if isinstance(payload, dict) else "Analyze failed."
                    final_status = message or "Analyze failed."
                    status_box.error(final_status)
                    continue
                if event_name == "done":
                    ok = bool(payload.get("ok")) if isinstance(payload, dict) else False
                    final_status = "Analyze completed." if ok else "Analyze finished with errors."
                    if ok:
                        status_box.success(final_status)
                    else:
                        status_box.warning(final_status)

            analyze_state["has_result"] = True
            analyze_state["image_name"] = uploaded.name
            analyze_state["image_bytes"] = uploaded.getvalue()
            analyze_state["meta"] = meta_payload
            analyze_state["summary"] = summary_text
            analyze_state["tags"] = tags
            analyze_state["ocr"] = ocr_text
            analyze_state["graph"] = graph_items
            analyze_state["suggestions"] = suggestions
            analyze_state["status"] = final_status
        finally:
            _complete_action("image_analyze")
            st.rerun()
        return

    if analyze_state.get("has_result"):
        st.markdown("### Uploaded Image")
        image_bytes = analyze_state.get("image_bytes")
        if image_bytes:
            st.image(image_bytes, caption=str(analyze_state.get("image_name", "")), width=320)

        st.markdown("### Analysis Result")
        st.markdown("**Meta**")
        st.json(analyze_state.get("meta", {}))
        st.markdown("**Summary**")
        st.write(str(analyze_state.get("summary", "")) or "-")
        st.markdown("**Tags**")
        st.write(analyze_state.get("tags", []))
        st.markdown("**OCR Text**")
        st.code(str(analyze_state.get("ocr", "")) or "-", language="text")
        st.markdown("**Graph Triples**")
        graph_items = analyze_state.get("graph", [])
        if isinstance(graph_items, list) and graph_items:
            st.dataframe(graph_items, use_container_width=True)
        else:
            st.info("No graph triples.")
        st.markdown("**Search Suggestions**")
        st.write(analyze_state.get("suggestions", []))
        status_text = str(analyze_state.get("status", ""))
        if status_text:
            st.success(status_text)


def render_vector_compare_page(base_url: str) -> None:
    st.subheader("Vector Compare")
    st.caption("Endpoint: POST /api/v1/vision/vector-compare")
    st.info("Compare semantic vectors for text-text, image-image, or image-text pairs.")
    st.caption("Image limit: up to 10MB per file. Backend will apply compression for embedding when needed.")

    left_type_store_key = "vector_left_type_store"
    left_type_widget_key = "vector_left_type_widget"
    right_type_store_key = "vector_right_type_store"
    right_type_widget_key = "vector_right_type_widget"
    left_text_store_key = "vector_left_text_store"
    left_text_widget_key = "vector_left_text_widget"
    right_text_store_key = "vector_right_text_store"
    right_text_widget_key = "vector_right_text_widget"
    if left_type_store_key not in st.session_state:
        st.session_state[left_type_store_key] = "text"
    if left_type_widget_key not in st.session_state:
        st.session_state[left_type_widget_key] = st.session_state[left_type_store_key]
    if right_type_store_key not in st.session_state:
        st.session_state[right_type_store_key] = "text"
    if right_type_widget_key not in st.session_state:
        st.session_state[right_type_widget_key] = st.session_state[right_type_store_key]
    if left_text_store_key not in st.session_state:
        st.session_state[left_text_store_key] = ""
    if left_text_widget_key not in st.session_state:
        st.session_state[left_text_widget_key] = st.session_state[left_text_store_key]
    if right_text_store_key not in st.session_state:
        st.session_state[right_text_store_key] = ""
    if right_text_widget_key not in st.session_state:
        st.session_state[right_text_widget_key] = st.session_state[right_text_store_key]

    left_col, right_col = st.columns(2)
    left_type = left_col.selectbox("Left Input Type", options=["text", "image"], key=left_type_widget_key)
    right_type = right_col.selectbox("Right Input Type", options=["text", "image"], key=right_type_widget_key)

    left_text = ""
    right_text = ""
    left_file = None
    right_file = None

    def _vector_compare_file_signature(uploaded: Any) -> dict[str, Any]:
        if uploaded is None:
            return {}
        file_bytes = uploaded.getvalue()
        return {
            "name": str(getattr(uploaded, "name", "")),
            "size": int(getattr(uploaded, "size", 0) or 0),
            "type": str(getattr(uploaded, "type", "")),
            "sha256": hashlib.sha256(file_bytes).hexdigest(),
        }

    if left_type == "text":
        left_text = st.text_area(
            "Left Text",
            placeholder="Input text for semantic compare...",
            height=110,
            key=left_text_widget_key,
        )
    else:
        left_file = st.file_uploader(
            "Left Image",
            type=["png", "jpg", "jpeg", "webp"],
            accept_multiple_files=False,
            key="vector_left_file_widget",
        )

    if right_type == "text":
        right_text = st.text_area(
            "Right Text",
            placeholder="Input text for semantic compare...",
            height=110,
            key=right_text_widget_key,
        )
    else:
        right_file = st.file_uploader(
            "Right Image",
            type=["png", "jpg", "jpeg", "webp"],
            accept_multiple_files=False,
            key="vector_right_file_widget",
        )
    st.session_state[left_type_store_key] = left_type
    st.session_state[right_type_store_key] = right_type
    if left_type == "text":
        st.session_state[left_text_store_key] = left_text
    if right_type == "text":
        st.session_state[right_text_store_key] = right_text

    compare_payload = {
        "leftType": left_type,
        "rightType": right_type,
        "leftText": left_text.strip() if left_type == "text" else "",
        "rightText": right_text.strip() if right_type == "text" else "",
        "leftFile": _vector_compare_file_signature(left_file),
        "rightFile": _vector_compare_file_signature(right_file),
    }
    submitted = st.button(
        "Run Vector Compare",
        type="primary",
        disabled=_is_action_busy("vector_compare", compare_payload),
    )

    compare_state_key = "vector_compare_state"
    if compare_state_key not in st.session_state or not isinstance(st.session_state[compare_state_key], dict):
        st.session_state[compare_state_key] = {
            "has_result": False,
            "request_data": {},
            "left_type": "text",
            "right_type": "text",
            "left_text": "",
            "right_text": "",
            "left_image_name": "",
            "right_image_name": "",
            "left_image_bytes": None,
            "right_image_bytes": None,
            "result": {},
            "status_code": 200,
        }
    compare_state = st.session_state[compare_state_key]

    if not submitted:
        if compare_state.get("has_result"):
            _render_vector_compare_result(compare_state)
    else:
        if left_type == "text" and not left_text.strip():
            st.warning("Left text is required.")
            return
        if right_type == "text" and not right_text.strip():
            st.warning("Right text is required.")
            return
        if left_type == "image" and left_file is None:
            st.warning("Please upload the left image.")
            return
        if right_type == "image" and right_file is None:
            st.warning("Please upload the right image.")
            return
        if left_type == "image" and not _validate_uploaded_image_size(left_file, "Left image"):
            return
        if right_type == "image" and not _validate_uploaded_image_size(right_file, "Right image"):
            return
        _queue_action("vector_compare", compare_payload)
        st.rerun()

    request_data = {
        "leftType": left_type,
        "rightType": right_type,
    }
    if left_type == "text":
        request_data["leftText"] = left_text.strip()
    if right_type == "text":
        request_data["rightText"] = right_text.strip()

    multipart_parts: dict[str, Any] = {
        "leftType": (None, left_type),
        "rightType": (None, right_type),
    }
    if left_type == "image" and left_file is not None:
        multipart_parts["leftFile"] = (
            left_file.name,
            left_file.getvalue(),
            left_file.type or "application/octet-stream",
        )
    elif left_type == "text":
        multipart_parts["leftText"] = (None, left_text.strip())
    if right_type == "image" and right_file is not None:
        multipart_parts["rightFile"] = (
            right_file.name,
            right_file.getvalue(),
            right_file.type or "application/octet-stream",
        )
    elif right_type == "text":
        multipart_parts["rightText"] = (None, right_text.strip())

    if _consume_queued_action("vector_compare", compare_payload):
        try:
            try:
                response = requests.post(
                    f"{_normalize_base_url(base_url)}/api/v1/vision/vector-compare",
                    files=multipart_parts,
                    timeout=REQUEST_TIMEOUT_SECONDS,
                )
            except requests.exceptions.Timeout:
                st.error("Request timed out. Please retry later.")
                return
            except requests.exceptions.RequestException as exc:
                st.error(f"Request failed: {exc}")
                return

            status_code = response.status_code
            st.caption(f"HTTP {status_code}")
            if response.status_code >= 400:
                st.error(f"HTTP {response.status_code}: {response.text[:600]}")
                return

            try:
                envelope = response.json()
            except ValueError:
                st.error("Backend returned non-JSON response.")
                return

            if not isinstance(envelope, dict):
                st.error("Unexpected response shape.")
                return

            if envelope.get("code") != SUCCESS_CODE:
                st.error(f"Business error {envelope.get('code')}: {envelope.get('message', 'Unknown error')}")
                return

            result = envelope.get("data")
            if not isinstance(result, dict):
                st.error("Unexpected response shape: data is not an object.")
                return

            compare_state["has_result"] = True
            compare_state["request_data"] = dict(request_data)
            compare_state["left_type"] = left_type
            compare_state["right_type"] = right_type
            compare_state["left_text"] = left_text.strip()
            compare_state["right_text"] = right_text.strip()
            compare_state["left_image_name"] = left_file.name if left_file is not None else ""
            compare_state["right_image_name"] = right_file.name if right_file is not None else ""
            compare_state["left_image_bytes"] = left_file.getvalue() if left_file is not None else None
            compare_state["right_image_bytes"] = right_file.getvalue() if right_file is not None else None
            compare_state["result"] = result
            compare_state["status_code"] = status_code
        finally:
            _complete_action("vector_compare")
            st.rerun()


def _render_vector_compare_result(compare_state: dict[str, Any]) -> None:
    status_code = int(compare_state.get("status_code", 200) or 200)
    st.caption(f"HTTP {status_code}")
    result = compare_state.get("result")
    if not isinstance(result, dict):
        return

    st.markdown("### Compare Result")
    metric_col1, metric_col2, metric_col3 = st.columns(3)
    metric_col1.metric("Cosine Similarity", str(result.get("cosineSimilarity", "-")))
    score_percent = result.get("scorePercent")
    metric_col2.metric("Score Percent", "-" if score_percent is None else f"{score_percent}%")
    metric_col3.metric("Match Level", str(result.get("matchLevel", "-")))

    st.markdown("### Input Preview")
    preview_col1, preview_col2 = st.columns(2)
    left_type = str(compare_state.get("left_type", "text"))
    right_type = str(compare_state.get("right_type", "text"))
    if left_type == "text":
        preview_col1.text_area("Left Value", value=str(compare_state.get("left_text", "")), height=120, disabled=True)
    else:
        left_image_bytes = compare_state.get("left_image_bytes")
        left_image_name = str(compare_state.get("left_image_name", "left_image"))
        if left_image_bytes:
            preview_col1.image(left_image_bytes, caption=left_image_name, width=320)
    if right_type == "text":
        preview_col2.text_area("Right Value", value=str(compare_state.get("right_text", "")), height=120, disabled=True)
    else:
        right_image_bytes = compare_state.get("right_image_bytes")
        right_image_name = str(compare_state.get("right_image_name", "right_image"))
        if right_image_bytes:
            preview_col2.image(right_image_bytes, caption=right_image_name, width=320)

    st.markdown("### Meta")
    st.json(result)

    if st.session_state.get("show_debug_panel", True):
        st.markdown("### Debug Request Payload")
        safe_payload = dict(compare_state.get("request_data", {}))
        if "leftText" in safe_payload:
            safe_payload["leftText"] = _clip_text(str(safe_payload["leftText"]), 200)
        if "rightText" in safe_payload:
            safe_payload["rightText"] = _clip_text(str(safe_payload["rightText"]), 200)
        st.code(_pretty_json(safe_payload), language="json")


def render_similar_search_page(base_url: str) -> None:
    st.subheader("Similar Search")
    st.caption("Endpoint: GET /api/v1/vision/similar")

    state = _get_result_state("similar")
    image_id_store_key = "similar_search_image_id_store"
    image_id_widget_key = "similar_search_image_id_widget"
    if image_id_store_key not in st.session_state:
        st.session_state[image_id_store_key] = ""
    if image_id_widget_key not in st.session_state:
        st.session_state[image_id_widget_key] = st.session_state[image_id_store_key]

    with st.form("similar_search_form"):
        image_id = st.text_input("Image ID", placeholder="e.g. 1234567890", key=image_id_widget_key)
        draft_payload = {"imageId": image_id.strip()}
        submitted = st.form_submit_button(
            "Run Similar Search",
            type="primary",
            disabled=_is_action_busy("similar_search", draft_payload),
        )
    st.session_state[image_id_store_key] = image_id

    if submitted:
        if not image_id.strip():
            st.warning("Image ID is required.")
            return
        _queue_action("similar_search", draft_payload)
        st.rerun()

    if _consume_queued_action("similar_search", draft_payload):
        try:
            with st.spinner("Searching similar images..."):
                response_payload = _request_json(
                    method="GET",
                    url=f"{_normalize_base_url(base_url)}/api/v1/vision/similar",
                    params={"id": image_id.strip()},
                )
            if response_payload is None:
                return
            data, headers, status_code = response_payload

            state["results"] = data
            state["headers"] = headers
            state["status_code"] = status_code
            state["payload_text"] = f"GET {_normalize_base_url(base_url)}/api/v1/vision/similar?id={image_id.strip()}"
            state["has_searched"] = True
        finally:
            _complete_action("similar_search")
            st.rerun()

    if state["has_searched"]:
        _render_response_meta(state["status_code"], state["headers"])
        if state["payload_text"]:
            st.code(state["payload_text"], language="bash")
        _render_search_results(state["results"], layout_key="similar")


def render_hot_words_page(base_url: str) -> None:
    st.subheader("Hot Words")
    st.caption("Endpoint: GET /api/v1/vision/hot-words")

    if "hot_words_state" not in st.session_state or not isinstance(st.session_state["hot_words_state"], dict):
        st.session_state["hot_words_state"] = {"has_loaded": False, "status_code": 200, "words": []}

    payload = {"endpoint": "hot_words"}
    if st.button("Fetch Hot Words", type="primary", disabled=_is_action_busy("hot_words", payload)):
        _queue_action("hot_words", payload)
        st.rerun()

    if _consume_queued_action("hot_words", payload):
        try:
            with st.spinner("Loading hot words..."):
                response_payload = _request_json(
                    method="GET",
                    url=f"{_normalize_base_url(base_url)}/api/v1/vision/hot-words",
                )
            if response_payload is None:
                return
            data, _, status_code = response_payload
            words = data if isinstance(data, list) else []
            st.session_state["hot_words_state"] = {
                "has_loaded": True,
                "status_code": status_code,
                "words": words,
            }
        finally:
            _complete_action("hot_words")
            st.rerun()

    hot_words_state = st.session_state["hot_words_state"]
    if hot_words_state.get("has_loaded"):
        st.caption(f"HTTP {hot_words_state.get('status_code', 200)}")
        words = hot_words_state.get("words")
        if isinstance(words, list) and words:
            st.markdown("### Hot Words")
            st.write(words)
        else:
            st.info("No hot words currently.")


def render_auth_admin_page(base_url: str) -> None:
    st.subheader("Auth Admin")
    st.caption("Endpoints: GET /api/v1/auth/refresh-token, GET /api/v1/auth/clean-token")
    st.warning("Admin operation: keep `X-Admin-Secret` private and do not expose in shared demos.")

    admin_secret_store_key = "auth_admin_secret_store"
    admin_secret_widget_key = "auth_admin_secret_widget"
    refresh_code_store_key = "auth_refresh_code_store"
    refresh_code_widget_key = "auth_refresh_code_widget"
    if admin_secret_store_key not in st.session_state:
        st.session_state[admin_secret_store_key] = ""
    if admin_secret_widget_key not in st.session_state:
        st.session_state[admin_secret_widget_key] = st.session_state[admin_secret_store_key]
    if refresh_code_store_key not in st.session_state:
        st.session_state[refresh_code_store_key] = ""
    if refresh_code_widget_key not in st.session_state:
        st.session_state[refresh_code_widget_key] = st.session_state[refresh_code_store_key]

    admin_secret = st.text_input("X-Admin-Secret", type="password", key=admin_secret_widget_key)
    refresh_code = st.text_input("Refresh Code (optional)", key=refresh_code_widget_key)
    st.session_state[admin_secret_store_key] = admin_secret
    st.session_state[refresh_code_store_key] = refresh_code

    col1, col2 = st.columns(2)
    if col1.button("Refresh Token", type="primary"):
        if not admin_secret.strip():
            st.warning("X-Admin-Secret is required.")
        else:
            with st.spinner("Refreshing token..."):
                params = {"code": refresh_code.strip()} if refresh_code.strip() else None
                _request_admin_endpoint(
                    base_url=_normalize_base_url(base_url),
                    path="/api/v1/auth/refresh-token",
                    admin_secret=admin_secret.strip(),
                    params=params,
                )

    if col2.button("Clean Token", type="secondary"):
        if not admin_secret.strip():
            st.warning("X-Admin-Secret is required.")
        else:
            with st.spinner("Cleaning token..."):
                _request_admin_endpoint(
                    base_url=_normalize_base_url(base_url),
                    path="/api/v1/auth/clean-token",
                    admin_secret=admin_secret.strip(),
                    params=None,
                )


def render_image_batch_process_page(base_url: str) -> None:
    st.subheader("Batch Process")

    mode_store_key = "batch_ingestion_mode_store"
    mode_widget_key = "batch_ingestion_mode_widget"
    if mode_store_key not in st.session_state:
        st.session_state[mode_store_key] = "image"
    if mode_widget_key not in st.session_state:
        st.session_state[mode_widget_key] = st.session_state[mode_store_key]

    ingestion_mode = st.selectbox(
        "Ingestion Type",
        options=list(BATCH_INGESTION_CONFIG.keys()),
        format_func=lambda mode: str(BATCH_INGESTION_CONFIG.get(mode, BATCH_INGESTION_CONFIG["image"]).get("label", mode)),
        key=mode_widget_key,
    )
    st.session_state[mode_store_key] = ingestion_mode
    mode_cfg = _get_batch_mode_config(ingestion_mode)
    batch_tasks_path = str(mode_cfg.get("batch_tasks_path", "/api/v1/image/batch-tasks"))
    accepted_extensions = list(mode_cfg.get("extensions", ["png", "jpg", "jpeg", "webp"]))

    st.caption(f"Endpoint: POST {batch_tasks_path}")
    st.info(
        "Integrated flow: fetch STS -> upload to OSS -> submit async task. "
        "Each item has independent status and can be retried manually when failed."
    )
    st.caption("File limit: up to 10MB per file.")
    st.caption("Accepted extensions: " + ", ".join(f".{ext}" for ext in accepted_extensions))

    if "batch_task_ids" not in st.session_state:
        st.session_state.batch_task_ids = []
    task_cache_key = "batch_task_cache"
    active_task_ids_key = "batch_active_task_ids"
    task_mode_map_key = "batch_task_mode_map"
    if task_cache_key not in st.session_state or not isinstance(st.session_state[task_cache_key], dict):
        st.session_state[task_cache_key] = {}
    if active_task_ids_key not in st.session_state or not isinstance(st.session_state[active_task_ids_key], list):
        st.session_state[active_task_ids_key] = []
    if task_mode_map_key not in st.session_state or not isinstance(st.session_state[task_mode_map_key], dict):
        st.session_state[task_mode_map_key] = {}
    active_statuses = {"PENDING", "RUNNING"}

    env_cfg = _load_env_config()
    token_store_key = "batch_token_store"
    token_widget_key = "batch_token_widget"
    key_prefix_store_key = "batch_key_prefix_store"
    key_prefix_widget_key = "batch_key_prefix_widget"
    bucket_store_key = "batch_bucket_store"
    bucket_widget_key = "batch_bucket_widget"
    endpoint_store_key = "batch_endpoint_store"
    endpoint_widget_key = "batch_endpoint_widget"
    encrypt_key_store_key = "batch_encrypt_key_store"
    encrypt_key_widget_key = "batch_encrypt_key_widget"
    encrypt_iv_store_key = "batch_encrypt_iv_store"
    encrypt_iv_widget_key = "batch_encrypt_iv_widget"
    auto_refresh_store_key = "batch_task_auto_refresh_store"
    auto_refresh_widget_key = "batch_task_auto_refresh_widget"
    if token_store_key not in st.session_state:
        st.session_state[token_store_key] = ""
    if token_widget_key not in st.session_state:
        st.session_state[token_widget_key] = st.session_state[token_store_key]
    if key_prefix_store_key not in st.session_state:
        st.session_state[key_prefix_store_key] = ""
    if key_prefix_widget_key not in st.session_state:
        st.session_state[key_prefix_widget_key] = st.session_state[key_prefix_store_key]
    if bucket_store_key not in st.session_state:
        st.session_state[bucket_store_key] = env_cfg.get("ALIYUN_OSS_BUCKET_NAME", "")
    if bucket_widget_key not in st.session_state:
        st.session_state[bucket_widget_key] = st.session_state[bucket_store_key]
    if endpoint_store_key not in st.session_state:
        st.session_state[endpoint_store_key] = env_cfg.get("OSS_ENDPOINT", "")
    if endpoint_widget_key not in st.session_state:
        st.session_state[endpoint_widget_key] = st.session_state[endpoint_store_key]
    if encrypt_key_store_key not in st.session_state:
        st.session_state[encrypt_key_store_key] = env_cfg.get("APP_ENCRYPT_KEY", "")
    if encrypt_key_widget_key not in st.session_state:
        st.session_state[encrypt_key_widget_key] = st.session_state[encrypt_key_store_key]
    if encrypt_iv_store_key not in st.session_state:
        st.session_state[encrypt_iv_store_key] = env_cfg.get("APP_ENCRYPT_IV", "")
    if encrypt_iv_widget_key not in st.session_state:
        st.session_state[encrypt_iv_widget_key] = st.session_state[encrypt_iv_store_key]
    if auto_refresh_store_key not in st.session_state:
        st.session_state[auto_refresh_store_key] = False
    if auto_refresh_widget_key not in st.session_state:
        st.session_state[auto_refresh_widget_key] = bool(st.session_state[auto_refresh_store_key])

    token = st.text_input("X-Access-Token", type="password", help="Required header for protected APIs", key=token_widget_key)
    key_prefix = st.text_input(
        "OSS Key Prefix",
        placeholder="Input manually, e.g. images/ui-upload/",
        key=key_prefix_widget_key,
    )
    files = st.file_uploader(
        "Select local files for integrated upload",
        type=accepted_extensions,
        accept_multiple_files=True,
        key="batch_files_widget",
    )

    bucket_name = st.text_input("OSS Bucket", key=bucket_widget_key)
    oss_endpoint = st.text_input("OSS Endpoint", key=endpoint_widget_key)
    encrypt_key = st.text_input(
        "APP_ENCRYPT_KEY (base64)",
        type="password",
        key=encrypt_key_widget_key,
    )
    encrypt_iv = st.text_input(
        "APP_ENCRYPT_IV (base64)",
        type="password",
        key=encrypt_iv_widget_key,
    )
    st.session_state[token_store_key] = token
    st.session_state[key_prefix_store_key] = key_prefix
    st.session_state[bucket_store_key] = bucket_name
    st.session_state[endpoint_store_key] = oss_endpoint
    st.session_state[encrypt_key_store_key] = encrypt_key
    st.session_state[encrypt_iv_store_key] = encrypt_iv

    if st.button("Upload To OSS And Submit Task", type="primary"):
        if not token.strip():
            st.warning("X-Access-Token is required.")
            return
        if not files:
            st.warning("Please select at least one file.")
            return
        oversize_files = [
            str(file.name)
            for file in files
            if getattr(file, "size", 0) > MAX_IMAGE_UPLOAD_BYTES
        ]
        if oversize_files:
            st.warning(
                "These files exceed 10MB and cannot be uploaded: "
                + ", ".join(oversize_files[:5])
                + (" ..." if len(oversize_files) > 5 else "")
            )
            return
        if not key_prefix.strip():
            st.warning("OSS Key Prefix is required.")
            return
        if not bucket_name.strip() or not oss_endpoint.strip():
            st.warning("OSS bucket and endpoint are required.")
            return
        if not encrypt_key.strip() or not encrypt_iv.strip():
            st.warning("APP_ENCRYPT_KEY and APP_ENCRYPT_IV are required for STS decryption.")
            return
        try:
            with st.spinner("Step 1/3: Fetching and decrypting STS token..."):
                sts = _fetch_and_decrypt_sts(
                    base_url=_normalize_base_url(base_url),
                    access_token=token.strip(),
                    encrypt_key_b64=encrypt_key.strip(),
                    encrypt_iv_b64=encrypt_iv.strip(),
                )
            if sts is None:
                return

            with st.spinner("Step 2/3: Uploading files to OSS..."):
                items = _upload_files_to_oss(
                    files=files,
                    key_prefix=key_prefix,
                    bucket_name=bucket_name.strip(),
                    endpoint=oss_endpoint.strip(),
                    sts=sts,
                    include_mime_type=ingestion_mode == "text",
                )
            if not items:
                return

            st.markdown("### Uploaded Items")
            st.dataframe(items, use_container_width=True)

            with st.spinner("Step 3/3: Submitting async task..."):
                envelope = _submit_batch_task(
                    base_url=_normalize_base_url(base_url),
                    access_token=token.strip(),
                    items=items,
                    batch_tasks_path=batch_tasks_path,
                )
            if envelope is None:
                return

            task = envelope.get("data") if isinstance(envelope, dict) else None
            task_id = task.get("taskId") if isinstance(task, dict) else None
            if isinstance(task_id, str) and task_id:
                if task_id not in st.session_state.batch_task_ids:
                    st.session_state.batch_task_ids.append(task_id)
                task_cache = st.session_state.get(task_cache_key)
                if not isinstance(task_cache, dict):
                    task_cache = {}
                if isinstance(task, dict):
                    task_cache[task_id] = task
                st.session_state[task_cache_key] = task_cache
                task_mode_map = st.session_state.get(task_mode_map_key)
                if not isinstance(task_mode_map, dict):
                    task_mode_map = {}
                task_mode_map[task_id] = ingestion_mode
                st.session_state[task_mode_map_key] = task_mode_map
                active_task_ids = st.session_state.get(active_task_ids_key)
                if not isinstance(active_task_ids, list):
                    active_task_ids = []
                initial_status = str(task.get("status", "PENDING")) if isinstance(task, dict) else "PENDING"
                if initial_status in active_statuses:
                    if task_id not in active_task_ids:
                        active_task_ids.append(task_id)
                else:
                    active_task_ids = [item for item in active_task_ids if item != task_id]
                st.session_state[active_task_ids_key] = active_task_ids
                st.success(f"Task submitted: {task_id}")
            else:
                st.warning("Task submitted, but taskId was not found in response.")
        except Exception as exc:
            st.error(f"Batch submit failed: {exc}")

    dashboard_placeholder = st.empty()
    active_tasks_placeholder = None
    auto_refresh = False
    has_active_task = False
    with dashboard_placeholder.container():
        st.markdown("### Task Dashboard")
        task_ids: list[str] = st.session_state.batch_task_ids
        if not task_ids:
            st.caption("No submitted tasks yet.")
            return

        control_col1, control_col2 = st.columns([1, 2])
        refresh_all = control_col1.button("Refresh Task Status")
        auto_refresh = control_col2.toggle("Auto refresh every 3 seconds", key=auto_refresh_widget_key)
        st.session_state[auto_refresh_store_key] = bool(auto_refresh)

        if not token.strip():
            st.warning("Input X-Access-Token to load task status and retry failed items.")
            return

        base = _normalize_base_url(base_url)
        task_cache = st.session_state.get(task_cache_key)
        if not isinstance(task_cache, dict):
            task_cache = {}
        task_mode_map = st.session_state.get(task_mode_map_key)
        if not isinstance(task_mode_map, dict):
            task_mode_map = {}
        active_task_ids = st.session_state.get(active_task_ids_key)
        if not isinstance(active_task_ids, list):
            active_task_ids = []
        normalized_task_ids = [str(task_id) for task_id in task_ids]
        active_task_set = {task_id for task_id in active_task_ids if task_id in normalized_task_ids}
        ordered_task_ids = list(reversed(normalized_task_ids))

        query_task_ids: list[str]
        if refresh_all:
            query_task_ids = list(ordered_task_ids)
        else:
            query_task_ids = [task_id for task_id in ordered_task_ids if task_id in active_task_set]
            for task_id in ordered_task_ids:
                if task_id not in task_cache and task_id not in query_task_ids:
                    query_task_ids.append(task_id)

        for task_id in query_task_ids:
            task_mode = str(task_mode_map.get(task_id, ingestion_mode))
            task_mode_cfg = _get_batch_mode_config(task_mode)
            task_batch_path = str(task_mode_cfg.get("batch_tasks_path", "/api/v1/image/batch-tasks"))
            task_payload = _get_batch_task_status(
                base_url=base,
                access_token=token.strip(),
                task_id=task_id,
                batch_tasks_path=task_batch_path,
            )
            if task_payload is None:
                continue
            task_data = task_payload.get("data")
            if not isinstance(task_data, dict):
                st.error(f"Invalid task data shape for task {task_id}")
                continue
            task_cache[task_id] = task_data
            task_status = str(task_data.get("status", ""))
            if task_status in active_statuses:
                active_task_set.add(task_id)
            else:
                active_task_set.discard(task_id)

        st.session_state[task_cache_key] = task_cache
        st.session_state[active_task_ids_key] = [task_id for task_id in normalized_task_ids if task_id in active_task_set]

        active_display_ids = [task_id for task_id in ordered_task_ids if task_id in active_task_set]
        completed_display_ids = [task_id for task_id in ordered_task_ids if task_id not in active_task_set]
        has_active_task = bool(active_display_ids)

        active_tasks_placeholder = st.empty()
        with active_tasks_placeholder.container():
            st.markdown("**Active Tasks**")
            if active_display_ids:
                for task_id in active_display_ids:
                    task_data = task_cache.get(task_id)
                    if not isinstance(task_data, dict):
                        st.caption(f"Task `{task_id}` is active. Waiting for status update...")
                        continue
                    task_mode = str(task_mode_map.get(task_id, ingestion_mode))
                    task_mode_cfg = _get_batch_mode_config(task_mode)
                    _render_single_batch_task(
                        task_data=task_data,
                        base_url=base,
                        access_token=token.strip(),
                        batch_tasks_path=str(task_mode_cfg.get("batch_tasks_path", "/api/v1/image/batch-tasks")),
                        ingestion_label=str(task_mode_cfg.get("label", "Image")),
                    )
            else:
                st.caption("No active tasks.")

        st.markdown("**Completed / History**")
        if completed_display_ids:
            for task_id in completed_display_ids:
                task_data = task_cache.get(task_id)
                if not isinstance(task_data, dict):
                    st.caption(f"Task `{task_id}` has no cached status yet. Click `Refresh Task Status`.")
                    continue
                task_mode = str(task_mode_map.get(task_id, ingestion_mode))
                task_mode_cfg = _get_batch_mode_config(task_mode)
                _render_single_batch_task(
                    task_data=task_data,
                    base_url=base,
                    access_token=token.strip(),
                    batch_tasks_path=str(task_mode_cfg.get("batch_tasks_path", "/api/v1/image/batch-tasks")),
                    ingestion_label=str(task_mode_cfg.get("label", "Image")),
                )
        else:
            st.caption("No completed tasks yet.")

    if auto_refresh and has_active_task:
        if active_tasks_placeholder is not None:
            active_tasks_placeholder.empty()
        time.sleep(3)
        st.rerun()


def _render_single_batch_task(
    *,
    task_data: dict[str, Any],
    base_url: str,
    access_token: str,
    batch_tasks_path: str,
    ingestion_label: str,
) -> None:
    task_id = str(task_data.get("taskId", ""))
    status = str(task_data.get("status", "UNKNOWN"))
    total = int(task_data.get("total", 0) or 0)
    success_count = int(task_data.get("successCount", 0) or 0)
    failure_count = int(task_data.get("failureCount", 0) or 0)
    running_count = int(task_data.get("runningCount", 0) or 0)
    pending_count = int(task_data.get("pendingCount", 0) or 0)
    done = success_count + failure_count
    progress = (float(done) / float(total)) if total > 0 else 0.0

    with st.container(border=True):
        st.markdown(f"**Task:** `{task_id}`")
        st.caption(f"Ingestion Type: {ingestion_label}")
        st.caption(
            f"Status: {status} | total={total} success={success_count} "
            f"failed={failure_count} running={running_count} pending={pending_count}"
        )
        st.progress(progress, text=f"{done}/{total} completed")

        items = task_data.get("items")
        if not isinstance(items, list):
            st.warning("Task item payload is invalid.")
            return

        rows: list[dict[str, Any]] = []
        failed_items: list[dict[str, Any]] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            item_status = str(item.get("status", "UNKNOWN"))
            rows.append(
                {
                    "itemId": item.get("itemId"),
                    "fileName": item.get("fileName"),
                    "status": item_status,
                    "retryCount": item.get("retryCount", 0),
                    "errorMessage": item.get("errorMessage"),
                    "key": item.get("key"),
                }
            )
            if item_status == "FAILED":
                failed_items.append(item)

        if failed_items:
            st.markdown("**Failed Items: Retry**")
            retry_all_payload = {"taskId": task_id, "failedCount": len(failed_items)}
            if st.button(
                f"Retry All Failed ({len(failed_items)})",
                key=f"retry_all_{task_id}",
                disabled=_is_action_busy("batch_retry_all", retry_all_payload),
            ):
                _queue_action("batch_retry_all", retry_all_payload)
                st.rerun()

            if _consume_queued_action("batch_retry_all", retry_all_payload):
                try:
                    with st.spinner("Retrying all failed items..."):
                        retried_all = _retry_all_failed_batch_task_items(
                            base_url=base_url,
                            access_token=access_token,
                            task_id=task_id,
                            batch_tasks_path=batch_tasks_path,
                        )
                    if retried_all is not None:
                        _mark_batch_task_active(task_id)
                        st.success("Retry-all submitted.")
                finally:
                    _complete_action("batch_retry_all")
                    st.rerun()

        st.markdown("**Items**")
        item_col_widths = [2.4, 1.0, 0.8, 3.0, 1.2]
        header = st.columns(item_col_widths)
        header[0].markdown("`fileName`")
        header[1].markdown("`status`")
        header[2].markdown("`retry`")
        header[3].markdown("`error`")
        header[4].markdown("`action`")

        for row in rows:
            file_name = str(row.get("fileName", ""))
            item_id = str(row.get("itemId", ""))
            status_text = str(row.get("status", ""))
            retry_count = int(row.get("retryCount", 0) or 0)
            error_text = str(row.get("errorMessage", "") or "")
            cols = st.columns(item_col_widths)
            cols[0].write(file_name)
            cols[1].write(status_text)
            cols[2].write(str(retry_count))
            cols[3].write(error_text if error_text else "-")

            if status_text == "FAILED" and item_id:
                retry_item_payload = {"taskId": task_id, "itemId": item_id}
                if cols[4].button(
                    "Retry",
                    key=f"retry_{task_id}_{item_id}",
                    disabled=_is_action_busy("batch_retry_item", retry_item_payload),
                ):
                    _queue_action("batch_retry_item", retry_item_payload)
                    st.rerun()

                if _consume_queued_action("batch_retry_item", retry_item_payload):
                    try:
                        with st.spinner(f"Retrying {file_name}..."):
                            retried = _retry_batch_task_item(
                                base_url=base_url,
                                access_token=access_token,
                                task_id=task_id,
                                item_id=item_id,
                                batch_tasks_path=batch_tasks_path,
                            )
                        if retried is not None:
                            _mark_batch_task_active(task_id)
                            st.success(f"Retry submitted for {file_name}")
                    finally:
                        _complete_action("batch_retry_item")
                        st.rerun()
            else:
                cols[4].write("-")


def _mark_batch_task_active(task_id: str) -> None:
    active_task_ids_key = "batch_active_task_ids"
    active_ids = st.session_state.get(active_task_ids_key)
    if not isinstance(active_ids, list):
        active_ids = []
    if task_id not in active_ids:
        active_ids.append(task_id)
    st.session_state[active_task_ids_key] = active_ids


def _request_admin_endpoint(
    *,
    base_url: str,
    path: str,
    admin_secret: str,
    params: dict[str, str] | None,
) -> None:
    try:
        response = requests.get(
            f"{base_url}{path}",
            headers={"X-Admin-Secret": admin_secret},
            params=params,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.RequestException as exc:
        st.error(f"Request failed: {exc}")
        return

    st.caption(f"HTTP {response.status_code}")
    try:
        payload = response.json()
        st.json(payload)
    except ValueError:
        st.code(response.text[:1000], language="text")


def _fetch_and_decrypt_sts(
    *,
    base_url: str,
    access_token: str,
    encrypt_key_b64: str,
    encrypt_iv_b64: str,
) -> dict[str, str] | None:
    try:
        response = requests.get(
            f"{base_url}/api/v1/auth/sts",
            headers={"X-Access-Token": access_token},
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.RequestException as exc:
        st.error(f"Failed to call /auth/sts: {exc}")
        return None

    if response.status_code >= 400:
        st.error(f"/auth/sts HTTP {response.status_code}: {response.text[:300]}")
        return None

    try:
        envelope = response.json()
    except ValueError:
        st.error("Invalid /auth/sts response: non-JSON")
        return None

    if not isinstance(envelope, dict) or envelope.get("code") != SUCCESS_CODE:
        st.error(f"/auth/sts business error: {envelope}")
        return None

    encrypted = envelope.get("data")
    if not isinstance(encrypted, str) or not encrypted.strip():
        st.error("Invalid /auth/sts payload: missing encrypted data")
        return None

    plain_text = _aes_cbc_pkcs5_decrypt_base64(
        ciphertext_b64=encrypted.strip(),
        key_b64=encrypt_key_b64,
        iv_b64=encrypt_iv_b64,
    )
    if plain_text is None:
        return None

    try:
        parsed = json.loads(plain_text)
    except ValueError as exc:
        st.error(f"Failed to parse STS JSON: {exc}")
        return None

    for field in ["accessKeyId", "accessKeySecret", "securityToken"]:
        value = parsed.get(field)
        if not isinstance(value, str) or not value:
            st.error(f"Decrypted STS missing field: {field}")
            return None

    return {
        "accessKeyId": parsed["accessKeyId"],
        "accessKeySecret": parsed["accessKeySecret"],
        "securityToken": parsed["securityToken"],
    }


def _load_env_config() -> dict[str, str]:
    cfg: dict[str, str] = {}
    env_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".env")

    if os.path.exists(env_path):
        try:
            with open(env_path, "r", encoding="utf-8") as f:
                for raw in f:
                    line = raw.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, value = line.split("=", 1)
                    cfg[key.strip()] = value.strip()
        except OSError:
            pass

    for key in ["ALIYUN_OSS_BUCKET_NAME", "OSS_ENDPOINT", "APP_ENCRYPT_KEY", "APP_ENCRYPT_IV"]:
        value = os.getenv(key)
        if value:
            cfg[key] = value

    return cfg


def _aes_cbc_pkcs5_decrypt_base64(*, ciphertext_b64: str, key_b64: str, iv_b64: str) -> str | None:
    try:
        from Crypto.Cipher import AES
        from Crypto.Util.Padding import unpad
    except ImportError:
        st.error("Missing dependency: pycryptodome. Please install requirements.")
        return None

    try:
        key = base64.b64decode(key_b64)
        iv = base64.b64decode(iv_b64)
        ciphertext = base64.b64decode(ciphertext_b64)
        cipher = AES.new(key, AES.MODE_CBC, iv)
        plain = unpad(cipher.decrypt(ciphertext), AES.block_size)
        return plain.decode("utf-8")
    except Exception as exc:
        st.error(f"Failed to decrypt STS payload: {exc}")
        return None


def _upload_files_to_oss(
    *,
    files: list[Any],
    key_prefix: str,
    bucket_name: str,
    endpoint: str,
    sts: dict[str, str],
    include_mime_type: bool = False,
) -> list[dict[str, str]]:
    try:
        import oss2
    except ImportError:
        st.error("Missing dependency: oss2. Please install requirements.")
        return []

    endpoint_url = endpoint if endpoint.startswith("http") else f"https://{endpoint}"
    auth = oss2.StsAuth(sts["accessKeyId"], sts["accessKeySecret"], sts["securityToken"])
    bucket = oss2.Bucket(auth, endpoint_url, bucket_name)

    items: list[dict[str, str]] = []
    for file in files:
        content = file.getvalue()
        md5_hex = hashlib.md5(content).hexdigest()
        object_key = f"{key_prefix.rstrip('/')}/{uuid.uuid4().hex}_{file.name}"
        try:
            bucket.put_object(object_key, content, headers={"Content-Type": file.type or "application/octet-stream"})
        except Exception as exc:
            st.error(f"OSS upload failed for {file.name}: {exc}")
            return []

        item = {
            "key": object_key,
            "fileName": file.name,
            "fileHash": md5_hex,
        }
        if include_mime_type and file.type:
            item["mimeType"] = str(file.type)
        items.append(item)

    return items


def _submit_batch_task(
    *,
    base_url: str,
    access_token: str,
    items: list[dict[str, str]],
    batch_tasks_path: str,
) -> dict[str, Any] | None:
    try:
        response = requests.post(
            f"{base_url}{batch_tasks_path}",
            headers={"X-Access-Token": access_token, "Content-Type": "application/json"},
            json=items,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.RequestException as exc:
        st.error(f"Failed to call {batch_tasks_path}: {exc}")
        return None

    st.caption(f"HTTP {response.status_code}")

    try:
        envelope = response.json()
    except ValueError:
        st.error("Invalid batch task response: non-JSON")
        st.code(response.text[:1000], language="text")
        return None

    if not isinstance(envelope, dict):
        st.error("Invalid batch task response shape")
        return None

    if envelope.get("code") != SUCCESS_CODE:
        st.error(f"Submit task failed: {envelope}")
        return None

    return envelope


def _get_batch_task_status(
    *,
    base_url: str,
    access_token: str,
    task_id: str,
    batch_tasks_path: str,
) -> dict[str, Any] | None:
    try:
        response = requests.get(
            f"{base_url}{batch_tasks_path}/{task_id}",
            headers={"X-Access-Token": access_token},
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.RequestException as exc:
        st.error(f"Failed to query task status: {exc}")
        return None

    try:
        envelope = response.json()
    except ValueError:
        st.error(f"Invalid task status response for task {task_id}")
        return None

    if not isinstance(envelope, dict):
        st.error(f"Invalid task status response shape for task {task_id}")
        return None
    if envelope.get("code") != SUCCESS_CODE:
        st.error(f"Query task status failed for {task_id}: {envelope}")
        return None
    return envelope


def _retry_batch_task_item(
    *,
    base_url: str,
    access_token: str,
    task_id: str,
    item_id: str,
    batch_tasks_path: str,
) -> dict[str, Any] | None:
    try:
        response = requests.post(
            f"{base_url}{batch_tasks_path}/{task_id}/items/{item_id}/retry",
            headers={"X-Access-Token": access_token},
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.RequestException as exc:
        st.error(f"Failed to retry item: {exc}")
        return None

    try:
        envelope = response.json()
    except ValueError:
        st.error("Invalid retry response: non-JSON")
        return None

    if not isinstance(envelope, dict):
        st.error("Invalid retry response shape")
        return None
    if envelope.get("code") != SUCCESS_CODE:
        st.error(f"Retry failed: {envelope}")
        return None

    return envelope


def _retry_all_failed_batch_task_items(
    *,
    base_url: str,
    access_token: str,
    task_id: str,
    batch_tasks_path: str,
) -> dict[str, Any] | None:
    try:
        response = requests.post(
            f"{base_url}{batch_tasks_path}/{task_id}/retry-failed",
            headers={"X-Access-Token": access_token},
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.RequestException as exc:
        st.error(f"Failed to retry all failed items: {exc}")
        return None

    try:
        envelope = response.json()
    except ValueError:
        st.error("Invalid retry-all response: non-JSON")
        return None

    if not isinstance(envelope, dict):
        st.error("Invalid retry-all response shape")
        return None
    if envelope.get("code") != SUCCESS_CODE:
        st.error(f"Retry-all failed: {envelope}")
        return None

    return envelope


def _get_batch_mode_config(mode: str) -> dict[str, Any]:
    return BATCH_INGESTION_CONFIG.get(mode, BATCH_INGESTION_CONFIG["image"])


def _request_json(
    method: str,
    url: str,
    *,
    params: dict[str, Any] | None = None,
    json: dict[str, Any] | None = None,
    files: dict[str, Any] | None = None,
) -> tuple[list[Any], dict[str, str], int] | None:
    try:
        response = requests.request(
            method=method,
            url=url,
            params=params,
            json=json,
            files=files,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.Timeout:
        st.error("Request timed out. Please check backend status and retry.")
        return None
    except requests.exceptions.RequestException as exc:
        st.error(f"Request failed: {exc}")
        return None

    status_code = response.status_code
    headers = {
        "X-Strategy-Requested": response.headers.get("X-Strategy-Requested", ""),
        "X-Strategy-Effective": response.headers.get("X-Strategy-Effective", ""),
        "X-Strategy-Fallback": response.headers.get("X-Strategy-Fallback", ""),
        "X-Strategy-Fallback-Reason": response.headers.get("X-Strategy-Fallback-Reason", ""),
    }

    if status_code >= 400:
        st.error(f"HTTP {status_code}: {response.text[:600]}")
        return None

    try:
        envelope = response.json()
    except ValueError:
        st.error("Backend returned non-JSON response.")
        return None

    if not isinstance(envelope, dict):
        st.error("Unexpected response shape: top-level JSON is not an object.")
        return None

    biz_code = envelope.get("code")
    message = envelope.get("message", "")
    data = envelope.get("data")

    if biz_code != SUCCESS_CODE:
        st.error(f"Business error {biz_code}: {message or 'Unknown error'}")
        return None

    if data is None:
        data = []

    if not isinstance(data, list):
        st.error("Unexpected response shape: data is not a list.")
        return None

    return data, headers, status_code


def _request_object_json(
    method: str,
    url: str,
    *,
    params: dict[str, Any] | None = None,
    json: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> tuple[Any, dict[str, str], int] | None:
    try:
        response = requests.request(
            method=method,
            url=url,
            params=params,
            json=json,
            headers=headers,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.Timeout:
        st.error("Request timed out. Please check backend status and retry.")
        return None
    except requests.exceptions.RequestException as exc:
        st.error(f"Request failed: {exc}")
        return None

    status_code = response.status_code
    debug_headers = {
        "X-Strategy-Requested": response.headers.get("X-Strategy-Requested", ""),
        "X-Strategy-Effective": response.headers.get("X-Strategy-Effective", ""),
        "X-Strategy-Fallback": response.headers.get("X-Strategy-Fallback", ""),
        "X-Strategy-Fallback-Reason": response.headers.get("X-Strategy-Fallback-Reason", ""),
    }

    if status_code >= 400:
        st.error(f"HTTP {status_code}: {response.text[:600]}")
        return None

    try:
        envelope = response.json()
    except ValueError:
        st.error("Backend returned non-JSON response.")
        return None

    if not isinstance(envelope, dict):
        st.error("Unexpected response shape: top-level JSON is not an object.")
        return None

    biz_code = envelope.get("code")
    message = envelope.get("message", "")
    data = envelope.get("data")
    if biz_code != SUCCESS_CODE:
        st.error(f"Business error {biz_code}: {message or 'Unknown error'}")
        return None
    return data, debug_headers, status_code


def _request_search_page(
    *,
    url: str,
    payload: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, str], int] | None:
    try:
        response = requests.post(
            url=url,
            json=payload,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.exceptions.Timeout:
        st.error("Request timed out. Please check backend status and retry.")
        return None
    except requests.exceptions.RequestException as exc:
        st.error(f"Request failed: {exc}")
        return None

    status_code = response.status_code
    headers = {
        "X-Strategy-Requested": response.headers.get("X-Strategy-Requested", ""),
        "X-Strategy-Effective": response.headers.get("X-Strategy-Effective", ""),
        "X-Strategy-Fallback": response.headers.get("X-Strategy-Fallback", ""),
        "X-Strategy-Fallback-Reason": response.headers.get("X-Strategy-Fallback-Reason", ""),
    }

    if status_code >= 400:
        st.error(f"HTTP {status_code}: {response.text[:600]}")
        return None

    try:
        envelope = response.json()
    except ValueError:
        st.error("Backend returned non-JSON response.")
        return None

    if not isinstance(envelope, dict):
        st.error("Unexpected response shape: top-level JSON is not an object.")
        return None

    biz_code = envelope.get("code")
    message = envelope.get("message", "")
    data = envelope.get("data")

    if biz_code != SUCCESS_CODE:
        st.error(f"Business error {biz_code}: {message or 'Unknown error'}")
        return None

    if data is None:
        data = {}
    if not isinstance(data, dict):
        st.error("Unexpected response shape: data is not an object.")
        return None

    items = data.get("items")
    if items is not None and not isinstance(items, list):
        st.error("Unexpected response shape: data.items is not a list.")
        return None
    return data, headers, status_code


def _iter_sse_events(response: requests.Response):
    event_name = "message"
    data_lines: list[str] = []
    for raw_line in response.iter_lines(decode_unicode=False):
        if raw_line is None:
            continue
        if isinstance(raw_line, bytes):
            line = raw_line.decode("utf-8", errors="replace").strip()
        else:
            line = str(raw_line).strip()
        if not line:
            if data_lines:
                payload_text = "\n".join(data_lines).strip()
                if payload_text:
                    yield event_name, _parse_sse_payload(payload_text)
            event_name = "message"
            data_lines = []
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_name = line[6:].strip() or "message"
            continue
        if line.startswith("data:"):
            data_lines.append(line[5:].strip())


def _parse_sse_payload(payload_text: str) -> Any:
    try:
        return json.loads(payload_text)
    except ValueError:
        return {"text": payload_text}


def _render_typewriter_delta(box: Any, base_text: str, delta: str) -> str:
    if not delta:
        box.write(base_text if base_text else "-")
        return base_text

    # Keep UI responsive for long content: animate short chunks, fast-append long chunks.
    should_fast_append = len(base_text) > 2000
    if should_fast_append:
        updated = base_text + delta
        box.write(updated if updated else "-")
        return updated

    updated = base_text
    for ch in delta:
        updated += ch
        box.write(f"{updated}▌")
        time.sleep(0.012)
    box.write(updated if updated else "-")
    return updated


def _render_response_meta(status_code: int, headers: dict[str, str]) -> None:
    st.caption(f"HTTP {status_code}")
    if any(headers.values()):
        st.markdown("#### Strategy Debug Headers")
        st.json({k: v for k, v in headers.items() if v})


def _render_search_results(
    results: list[Any],
    *,
    layout_key: str,
    requested_strategy: str = "",
) -> None:
    st.markdown("### Results")
    if not results:
        st.info("No results found.")
        return

    pref_key = f"result_layout_pref_{layout_key}"
    if pref_key not in st.session_state:
        st.session_state[pref_key] = LAYOUT_OPTIONS[0]

    current_pref = st.session_state[pref_key]
    if current_pref not in LAYOUT_OPTIONS:
        current_pref = LAYOUT_OPTIONS[0]

    layout_mode = st.radio(
        "Result Layout",
        options=LAYOUT_OPTIONS,
        index=LAYOUT_OPTIONS.index(current_pref),
        horizontal=True,
        key=f"result_layout_mode_{layout_key}",
    )
    st.session_state[pref_key] = layout_mode

    num_cols = 2 if layout_mode.startswith("Large") else 3

    normalized_results = [item for item in results if isinstance(item, dict)]
    if not normalized_results:
        st.error("Unexpected response shape: result items are not objects.")
        return

    _inject_result_text_styles()
    _render_search_results_balanced_columns(
        normalized_results,
        num_cols=num_cols,
        layout_key=layout_key,
        requested_strategy=requested_strategy,
    )


def _build_result_card_html(item: dict[str, Any], *, requested_strategy: str = "") -> str:
    image_url = _safe_str(item.get("url"))
    filename = _safe_str(item.get("filename")) or "Unnamed"
    doc_id = _safe_str(item.get("id")) or "-"
    score = "-" if item.get("score") is None else str(item.get("score"))
    tags = item.get("tags")
    tags_text = ", ".join(str(tag) for tag in tags[:8]) if isinstance(tags, list) and tags else "-"
    field_highlights = _normalize_field_highlights(item.get("highlights"))
    filename_terms = _resolve_field_highlight_terms(field_highlights, ["fileName"])
    tags_terms = _resolve_field_highlight_terms(field_highlights, ["tags"])
    ocr_terms = _resolve_field_highlight_terms(field_highlights, ["ocrContent"])
    spo_terms = _resolve_field_highlight_terms(field_highlights, ["relations", "relations.s", "relations.p", "relations.o"])
    ocr_text = _clip_text(_safe_str(item.get("ocrText")), OCR_PREVIEW_MAX_CHARS)
    relations_text = _clip_text(
        _format_relations_preview(item.get("relations"), max_items=SPO_PREVIEW_MAX_ITEMS),
        SPO_PREVIEW_MAX_CHARS,
    )
    explain = item.get("explain") if isinstance(item.get("explain"), dict) else {}
    hit_sources = _normalize_hit_sources(explain.get("hitSources"))
    hit_sources_html = _render_hit_source_badges(hit_sources)
    strategy_effective = _safe_str(explain.get("strategyEffective")) or "-"
    tags_html = _highlight_text_html_by_terms(tags_text, tags_terms)
    ocr_html = _highlight_text_html_by_terms(ocr_text, ocr_terms) if ocr_text else ""
    spo_html = _highlight_text_html_by_terms(relations_text, spo_terms) if relations_text else ""

    card_html = [
        "<div class='result-card'>",
        _render_large_preview(
            image_url,
            filename,
            highlight_terms=filename_terms,
        ),
        "<div class='result-meta'>",
    ]
    card_html.append(_meta_row("id", doc_id))
    card_html.append(_meta_row("score", score))
    card_html.append(_meta_row_html("tags", tags_html))
    if relations_text:
        card_html.append(_meta_row_single_line_html("graph", spo_html, relations_text))
    if _should_show_strategy_mismatch(strategy_effective, requested_strategy):
        card_html.append(_meta_row("strategy", strategy_effective))
    if ocr_text:
        card_html.append(_meta_row_html("ocrText", ocr_html))
    if hit_sources_html:
        card_html.append(_meta_row_html("hitSources", hit_sources_html))
    card_html.append("</div></div>")
    return "".join(card_html)


def _render_search_results_balanced_columns(
    results: list[dict[str, Any]],
    *,
    num_cols: int,
    layout_key: str,
    requested_strategy: str = "",
) -> None:
    safe_col_count = max(1, int(num_cols))
    state_key = f"result_layout_columns_state_{layout_key}_{safe_col_count}"
    layout_state = st.session_state.get(state_key)
    if not isinstance(layout_state, dict):
        layout_state = {}

    previous_map = layout_state.get("col_map")
    if not isinstance(previous_map, dict):
        previous_map = {}

    col_items: list[list[dict[str, Any]]] = [[] for _ in range(safe_col_count)]
    col_heights = [0 for _ in range(safe_col_count)]
    current_map: dict[str, int] = {}
    id_seen_count: dict[str, int] = {}

    for idx, item in enumerate(results):
        item_key = _resolve_result_layout_key(item, idx, id_seen_count)
        estimated_height = _estimate_result_card_height(item, requested_strategy=requested_strategy)
        mapped_col = previous_map.get(item_key)
        if isinstance(mapped_col, int) and 0 <= mapped_col < safe_col_count:
            col_idx = mapped_col
        else:
            col_idx = min(range(safe_col_count), key=lambda i: (col_heights[i], i))

        col_items[col_idx].append(item)
        col_heights[col_idx] += estimated_height
        current_map[item_key] = col_idx

    st.session_state[state_key] = {"col_map": current_map}

    cols = st.columns(safe_col_count)
    for col_idx, col in enumerate(cols):
        with col:
            for item in col_items[col_idx]:
                st.markdown(
                    _build_result_card_html(item, requested_strategy=requested_strategy),
                    unsafe_allow_html=True,
                )


def _resolve_result_layout_key(item: dict[str, Any], idx: int, id_seen_count: dict[str, int]) -> str:
    raw_id = _safe_str(item.get("id"))
    if raw_id:
        seq = id_seen_count.get(raw_id, 0)
        id_seen_count[raw_id] = seq + 1
        return raw_id if seq == 0 else f"{raw_id}#{seq}"
    return f"idx:{idx}"


def _estimate_result_card_height(item: dict[str, Any], *, requested_strategy: str = "") -> int:
    estimated = 220

    tags = item.get("tags")
    tags_text = ", ".join(str(tag) for tag in tags[:8]) if isinstance(tags, list) and tags else "-"
    tags_len = len(tags_text)
    estimated += 14 + min(44, (tags_len // 28) * 10)

    ocr_text = _clip_text(_safe_str(item.get("ocrText")), OCR_PREVIEW_MAX_CHARS)
    if ocr_text:
        estimated += 18 + min(64, (len(ocr_text) // 26) * 10)

    relations_text = _clip_text(
        _format_relations_preview(item.get("relations"), max_items=SPO_PREVIEW_MAX_ITEMS),
        SPO_PREVIEW_MAX_CHARS,
    )
    if relations_text:
        estimated += 18

    explain = item.get("explain") if isinstance(item.get("explain"), dict) else {}
    strategy_effective = _safe_str(explain.get("strategyEffective")) or "-"
    if _should_show_strategy_mismatch(strategy_effective, requested_strategy):
        estimated += 16

    hit_sources = _normalize_hit_sources(explain.get("hitSources"))
    if hit_sources:
        estimated += 14 + (len(hit_sources) // 3) * 8

    return estimated


def _format_relations_preview(relations: Any, *, max_items: int) -> str:
    rows = _normalize_relations(relations)
    if not rows:
        return ""

    triples = [_format_relation_line(row, delimiter="-") for row in rows[:max_items]]
    return "; ".join(triples)


def _highlight_text_html_by_terms(text: str, terms: list[str]) -> str:
    safe_text = html.escape(_safe_str(text))
    if not safe_text:
        return safe_text
    deduped_terms = _dedupe_terms(terms)
    if not deduped_terms:
        return safe_text
    pattern = re.compile("|".join(re.escape(term) for term in deduped_terms), re.IGNORECASE)
    return pattern.sub(lambda m: f"<mark class='hit-mark'>{m.group(0)}</mark>", safe_text)


def _normalize_field_highlights(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    normalized: dict[str, str] = {}
    for key, snippet in value.items():
        field = _safe_str(key)
        if not field:
            continue
        if not isinstance(snippet, str):
            continue
        raw = snippet.strip()
        if not raw:
            continue
        normalized[field] = raw
    return normalized


def _resolve_field_highlight_terms(highlights: dict[str, str], fields: list[str]) -> list[str]:
    terms: list[str] = []
    for field in fields:
        snippet = highlights.get(field)
        if not snippet:
            continue
        terms.extend(_extract_marked_terms_from_snippet(snippet))
    return _dedupe_terms(terms)


def _extract_marked_terms_from_snippet(snippet: str) -> list[str]:
    raw = _safe_str(snippet)
    if not raw:
        return []
    terms: list[str] = []
    for pattern in [
        r"<em>(.*?)</em>",
        r"<strong>(.*?)</strong>",
        r"&lt;em&gt;(.*?)&lt;/em&gt;",
        r"&lt;strong&gt;(.*?)&lt;/strong&gt;",
    ]:
        for match in re.findall(pattern, raw, flags=re.IGNORECASE | re.DOTALL):
            term = html.unescape(_safe_str(match))
            if term:
                terms.append(term)
    return _dedupe_terms(terms)


def _dedupe_terms(terms: list[str]) -> list[str]:
    cleaned = [_safe_str(term) for term in terms if _safe_str(term)]
    if not cleaned:
        return []
    # Prefer longer terms first to reduce partial-overlap replacements.
    ordered = sorted(cleaned, key=lambda term: len(term), reverse=True)
    seen: set[str] = set()
    result: list[str] = []
    for term in ordered:
        key = term.lower()
        if key in seen:
            continue
        seen.add(key)
        result.append(term)
    return result


def _normalize_relations(relations: Any) -> list[dict[str, str]]:
    if not isinstance(relations, list):
        return []

    rows: list[dict[str, str]] = []
    for triple in relations:
        if not isinstance(triple, dict):
            continue
        s = _safe_str(triple.get("s"))
        p = _safe_str(triple.get("p"))
        o = _safe_str(triple.get("o"))
        if not (s or p or o):
            continue
        rows.append({"s": s, "p": p, "o": o})
    return rows


def _format_relation_line(row: dict[str, str], *, delimiter: str) -> str:
    parts = [part for part in [row.get("s", ""), row.get("p", ""), row.get("o", "")] if part]
    return delimiter.join(parts)


def _safe_str(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


def _normalize_hit_sources(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    normalized: list[str] = []
    for item in value:
        source = _safe_str(item).upper()
        if source in {"VECTOR", "FILENAME", "OCR", "TAG", "GRAPH"} and source not in normalized:
            normalized.append(source)
    return normalized


def _render_hit_source_badges(hit_sources: list[str]) -> str:
    if not hit_sources:
        return ""
    return "".join(
        f"<span class='hit-source-badge hit-source-{source.lower()}'>{html.escape(source)}</span>"
        for source in hit_sources
    )


def _should_show_strategy_mismatch(strategy_effective: str, requested_strategy: str) -> bool:
    effective = _safe_str(strategy_effective)
    requested = _safe_str(requested_strategy)
    if not effective or effective == "-" or not requested:
        return False
    return effective != requested


def _validate_uploaded_image_size(uploaded: Any, label: str) -> bool:
    if uploaded is None:
        return False
    size = int(getattr(uploaded, "size", 0) or 0)
    if size <= 0:
        return True
    if size > MAX_IMAGE_UPLOAD_BYTES:
        st.warning(f"{label} is too large ({size / 1024 / 1024:.2f}MB). Please upload an image within 10MB.")
        return False
    return True


def _uploaded_file_fingerprint(uploaded: Any) -> dict[str, Any]:
    if uploaded is None:
        return {}
    return {
        "name": str(getattr(uploaded, "name", "")),
        "size": int(getattr(uploaded, "size", 0) or 0),
        "type": str(getattr(uploaded, "type", "")),
    }


def _idempotent_signature(payload: Any) -> str:
    normalized_payload = json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(normalized_payload.encode("utf-8")).hexdigest()


def _get_action_registry() -> dict[str, Any]:
    key = "__action_registry"
    registry = st.session_state.get(key)
    if not isinstance(registry, dict):
        registry = {}
        st.session_state[key] = registry
    return registry


def _is_action_busy(action_key: str, payload: Any) -> bool:
    registry = _get_action_registry()
    record = registry.get(action_key)
    if not isinstance(record, dict):
        return False
    status = str(record.get("status", "idle"))
    now = time.time()
    queued_at = float(record.get("queuedAt", 0) or 0)
    running_at = float(record.get("runningAt", 0) or 0)
    if status == "queued" and queued_at > 0 and now - queued_at > ACTION_STALE_TIMEOUT_SECONDS:
        registry.pop(action_key, None)
        return False
    if status == "running" and running_at > 0 and now - running_at > ACTION_STALE_TIMEOUT_SECONDS:
        registry.pop(action_key, None)
        return False
    signature = str(record.get("signature", ""))
    return status in {"queued", "running"} and signature == _idempotent_signature(payload)


def _queue_action(action_key: str, payload: Any) -> None:
    _get_action_registry()[action_key] = {
        "status": "queued",
        "signature": _idempotent_signature(payload),
        "queuedAt": time.time(),
    }


def _consume_queued_action(action_key: str, payload: Any) -> bool:
    record = _get_action_registry().get(action_key)
    if not isinstance(record, dict):
        return False
    if str(record.get("status", "")) != "queued":
        return False
    if str(record.get("signature", "")) != _idempotent_signature(payload):
        return False
    record["status"] = "running"
    record["runningAt"] = time.time()
    return True


def _complete_action(action_key: str) -> None:
    _get_action_registry().pop(action_key, None)


def _normalize_base_url(base_url: str) -> str:
    return (base_url or "http://localhost:8080").strip().rstrip("/")


def _pretty_json(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, indent=2)


def _get_result_state(page: str) -> dict[str, Any]:
    key = RESULT_STATE_KEYS[page]
    if key not in st.session_state:
        st.session_state[key] = {
            "has_searched": False,
            "results": [],
            "headers": {},
            "status_code": 200,
            "payload_text": "",
        }
    return st.session_state[key]


def _render_large_preview(
    image_url: str,
    filename: str,
    highlight_terms: list[str] | None = None,
) -> str:
    terms = _dedupe_terms(highlight_terms or [])
    if terms:
        safe_filename = _highlight_text_html_by_terms(filename, terms)
    else:
        safe_filename = html.escape(filename)
    if image_url:
        safe_image_url = html.escape(image_url, quote=True)
        return (
            "<div class='result-image-wrap'>"
            f"<img src=\"{safe_image_url}\" class='result-image' />"
            f"<div class='result-filename'>{safe_filename}</div>"
            "</div>"
        )
    return (
        "<div class='result-image-wrap result-image-empty'>"
        "<div class='result-no-image'>No image URL</div>"
        f"<div class='result-filename'>{safe_filename}</div>"
        "</div>"
    )


def _inject_result_text_styles() -> None:
    st.markdown(
        """
        <style>
        .result-card {
          margin-top: 4px;
          border: 1px solid rgba(120, 130, 150, 0.32);
          border-radius: 12px;
          background: rgba(255, 255, 255, 0.03);
          overflow: hidden;
        }
        .result-image-wrap {
          position: relative;
          width: 100%;
          background: rgba(13, 18, 28, 0.72);
        }
        .result-image {
          width: 100%;
          height: auto;
          display: block;
        }
        .result-image-empty {
          min-height: 160px;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .result-no-image {
          color: #8b9bb0;
          font-size: 12px;
        }
        .result-filename {
          position: absolute;
          right: 8px;
          bottom: 8px;
          max-width: calc(100% - 16px);
          padding: 2px 6px;
          border-radius: 6px;
          font-size: 11px;
          line-height: 1.2;
          color: #f4f8ff;
          background: rgba(12, 16, 24, 0.78);
          backdrop-filter: blur(2px);
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
        .result-meta {
          padding: 6px 8px 7px;
          border-top: 1px solid rgba(120, 130, 150, 0.2);
          background: rgba(4, 8, 14, 0.3);
        }
        .result-row {
          margin: 1px 0;
          line-height: 1.18;
          font-size: 11px;
          display: flex;
          align-items: flex-start;
          gap: 7px;
        }
        .result-key {
          min-width: 56px;
          color: #8ea0b6;
          font-weight: 700;
          letter-spacing: 0.01em;
          text-transform: none;
        }
        .result-val {
          color: #e7eef7;
          font-weight: 400;
          word-break: break-word;
          overflow-wrap: anywhere;
        }
        .result-val-one-line {
          color: #e7eef7;
          font-weight: 400;
          display: block;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
        .hit-mark {
          background: rgba(255, 216, 107, 0.92);
          color: #2b1f00;
          border-radius: 3px;
          padding: 0 4px;
        }
        .hit-source-badge {
          display: inline-block;
          margin-right: 4px;
          margin-bottom: 3px;
          padding: 1px 6px;
          border-radius: 999px;
          font-size: 10px;
          font-weight: 700;
          letter-spacing: 0.02em;
          color: #0d1522;
          background: #9fb4cf;
        }
        .hit-source-vector { background: #7dd3fc; }
        .hit-source-filename { background: #c4b5fd; }
        .hit-source-ocr { background: #86efac; }
        .hit-source-tag { background: #fcd34d; }
        .hit-source-graph { background: #f9a8d4; }
        </style>
        """,
        unsafe_allow_html=True,
    )


def _meta_row(key: str, value: str) -> str:
    return (
        "<div class='result-row'>"
        f"<span class='result-key'>{html.escape(key)}:</span>"
        f"<span class='result-val'>{html.escape(value)}</span>"
        "</div>"
    )


def _meta_row_single_line(key: str, value: str) -> str:
    safe_value = html.escape(value)
    return (
        "<div class='result-row'>"
        f"<span class='result-key'>{html.escape(key)}:</span>"
        f"<span class='result-val-one-line' title='{safe_value}'>{safe_value}</span>"
        "</div>"
    )


def _meta_row_html(key: str, value_html: str) -> str:
    return (
        "<div class='result-row'>"
        f"<span class='result-key'>{html.escape(key)}:</span>"
        f"<span class='result-val'>{value_html}</span>"
        "</div>"
    )


def _meta_row_single_line_html(key: str, value_html: str, raw_value: str) -> str:
    return (
        "<div class='result-row'>"
        f"<span class='result-key'>{html.escape(key)}:</span>"
        f"<span class='result-val-one-line' title='{html.escape(raw_value)}'>{value_html}</span>"
        "</div>"
    )


def _clip_text(text: str, max_chars: int) -> str:
    if not text:
        return ""
    text = text.strip()
    return text if len(text) <= max_chars else text[:max_chars] + "..."
