import streamlit as st
from pathlib import Path

from pages import render_hot_words_page
from pages import render_auth_admin_page
from pages import render_image_analyze_page
from pages import render_image_batch_process_page
from pages import render_conversation_search_page
from pages import render_kb_search_page
from pages import render_image_search_page
from pages import render_similar_search_page
from pages import render_text_search_page
from pages import render_vector_compare_page

st.set_page_config(
    page_title="Smart Vision Frontend Validation",
    page_icon="🖼️",
    layout="wide",
)


def init_state() -> None:
    if "base_url" not in st.session_state:
        st.session_state.base_url = "http://localhost:8080"
    if "theme_mode" not in st.session_state:
        st.session_state.theme_mode = "Light"
    if "show_debug_panel" not in st.session_state:
        st.session_state.show_debug_panel = True
    if "request_timeout_seconds" not in st.session_state:
        st.session_state.request_timeout_seconds = 20
    if "theme_mode_prev" not in st.session_state:
        st.session_state.theme_mode_prev = st.session_state.theme_mode


def write_streamlit_theme_config(theme_mode: str) -> None:
    config_dir = Path(__file__).resolve().parent / ".streamlit"
    config_dir.mkdir(parents=True, exist_ok=True)
    config_file = config_dir / "config.toml"

    mode = (theme_mode or "System").strip().lower()
    if mode == "dark":
        base = "dark"
    elif mode == "light":
        base = "light"
    else:
        # Streamlit has no explicit 'system' base in config; keep light as default fallback.
        base = "light"

    config_file.write_text(
        "\n".join(
            [
                "[theme]",
                f'base = "{base}"',
                'primaryColor = "#1677ff"',
                'backgroundColor = "#ffffff"' if base == "light" else 'backgroundColor = "#0e1117"',
                'secondaryBackgroundColor = "#f0f2f6"' if base == "light" else 'secondaryBackgroundColor = "#1b1f2a"',
                'textColor = "#142235"' if base == "light" else 'textColor = "#e7eef8"',
            ]
        )
        + "\n",
        encoding="utf-8",
    )


def render_sidebar() -> str:
    with st.sidebar:
        st.title("Smart Vision")
        st.caption("Streamlit validation UI")
        st.session_state.base_url = st.text_input(
            "Backend Base URL",
            value=st.session_state.base_url,
            help="Example: http://localhost:8080",
        ).strip()
        selected_theme_mode = st.selectbox(
            "Theme",
            ["Light", "Dark", "System"],
            index=["Light", "Dark", "System"].index(st.session_state.theme_mode),
        )
        st.session_state.theme_mode = selected_theme_mode
        if st.session_state.theme_mode != st.session_state.theme_mode_prev:
            write_streamlit_theme_config(st.session_state.theme_mode)
            st.session_state.theme_mode_prev = st.session_state.theme_mode
            st.info("Theme config saved. Please refresh/restart Streamlit to apply native theme.")
        st.divider()
        st.session_state.show_debug_panel = st.checkbox(
            "Show Debug Panel",
            value=st.session_state.show_debug_panel,
            help="Display request logs and latest request/response details on each page.",
        )
        st.session_state.request_timeout_seconds = int(
            st.number_input(
                "Request Timeout (s)",
                min_value=5,
                max_value=120,
                value=int(st.session_state.request_timeout_seconds),
                step=1,
            )
        )
        page = st.radio(
            "Pages",
            [
                "Text Search",
                "KB Search",
                "Conversation Search",
                "Search By Image",
                "Similar Search",
                "Hot Words",
                "Batch Process",
                "Auth Admin",
                "Image Analyze",
                "Vector Compare",
            ],
        )
    return page


def main() -> None:
    init_state()
    config_file = Path(__file__).resolve().parent / ".streamlit" / "config.toml"
    if not config_file.exists():
        write_streamlit_theme_config(st.session_state.theme_mode)
    current_page = render_sidebar()

    st.title("Smart Vision Frontend Validation")
    # st.caption("UI-TASK-05: pagination, resilience, and debugability")
    st.divider()

    if current_page == "Text Search":
        render_text_search_page(st.session_state.base_url)
        return
    if current_page == "KB Search":
        render_kb_search_page(st.session_state.base_url)
        return
    if current_page == "Conversation Search":
        render_conversation_search_page(st.session_state.base_url)
        return
    if current_page == "Search By Image":
        render_image_search_page(st.session_state.base_url)
        return
    if current_page == "Image Analyze":
        render_image_analyze_page(st.session_state.base_url)
        return
    if current_page == "Vector Compare":
        render_vector_compare_page(st.session_state.base_url)
        return
    if current_page == "Similar Search":
        render_similar_search_page(st.session_state.base_url)
        return
    if current_page == "Batch Process":
        render_image_batch_process_page(st.session_state.base_url)
        return
    if current_page == "Auth Admin":
        render_auth_admin_page(st.session_state.base_url)
        return
    render_hot_words_page(st.session_state.base_url)


if __name__ == "__main__":
    main()
