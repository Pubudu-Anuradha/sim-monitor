# Source from the repo root:  . ./env.sh
# Keeps every project dependency and cache inside .devenv/ (gitignored).
_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export SIM_MONITOR_ROOT="$_root"
export UV_INSTALL_DIR="$_root/.devenv/bin"
export UV_CACHE_DIR="$_root/.devenv/uv-cache"
export UV_PYTHON_INSTALL_DIR="$_root/.devenv/python"
export UV_PYTHON_PREFERENCE=only-managed
export UV_NO_MODIFY_PATH=1
export UV_PYTHON_BIN_DIR="$_root/.devenv/bin"
export UV_TOOL_DIR="$_root/.devenv/uv-tools"
export UV_TOOL_BIN_DIR="$_root/.devenv/bin"
export GRADLE_USER_HOME="$_root/.devenv/gradle-home"
export npm_config_cache="$_root/.devenv/npm-cache"
case ":$PATH:" in *":$_root/.devenv/bin:"*) ;; *) export PATH="$_root/.devenv/bin:$PATH" ;; esac
unset _root
