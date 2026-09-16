// ============================================================
// 数据下载页逻辑
//
// 由独立「水质检测」项目的 pages/plugin/nav_datadisplay.js 迁移而来。
// 主要差异：
//   1. 后端调用从通用命令分发 POST /api/localcommand {tag, cmd}
//      改为现场风格的 REST：data-transfer/*
//   2. 统一走 ApiManager 的 axiosClient，自动带上 token 并处理登录态
//   3. 导入增加「允许覆盖现有表」二次确认
// ============================================================

import axiosClient from "../../../../../api/ApiManager.js";

const EXPORT_TARGETS = {
  sensorSql:       { label: "水质数据 SQL", ext: "sql", prefix: "sensor_data" },
  sensorCsv:       { label: "水质数据 CSV", ext: "csv", prefix: "sensor_data" },
  stationSql:      { label: "站点 SQL",     ext: "sql", prefix: "station" },
  routeSql:        { label: "路线 SQL",     ext: "sql", prefix: "route" },
  stationRouteSql: { label: "站点/路线 SQL", ext: "sql", prefix: "station_route" }
};

// ---------- 目录浏览状态 ----------
const currentDir = { export: "", import: "" };
let selectedImportFile = "";

// ============================================================
// 通用工具
// ============================================================

/**
 * 取后端错误文案。
 *
 * 注意现场后端 ResultUtils.error(ErrorCode, description) 的实现会把描述写进
 * message、把枚举自带文案写进 data，因此这里按 message → description → data 依次兜底。
 */
function describeError(resp) {
  const body = (resp && resp.data) || {};
  if (body.message) return body.message;
  if (body.description) return body.description;
  if (typeof body.data === "string" && body.data) return body.data;
  return "请求失败";
}

/**
 * 统一调用后端：成功返回 data，失败抛出带文案的 Error。
 */
async function callApi(request, failurePrefix) {
  let resp;
  try {
    resp = await request;
  } catch (e) {
    const detail = e && e.message ? e.message : String(e);
    throw new Error((failurePrefix || "接口调用失败") + ": " + detail);
  }
  const body = resp.data || {};
  if (body.code !== 0) {
    throw new Error(describeError(resp));
  }
  return body.data;
}

function downloadText(content, filename, mimeType) {
  const blob = new Blob([content], { type: mimeType || "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/** 触发浏览器下载后端提供的文件（日志面板用） */
function downloadByPath(path) {
  const a = document.createElement("a");
  a.href = "data-transfer/download?path=" + encodeURIComponent(path);
  a.download = "";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function timestamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) +
    "_" + pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds());
}

function escapeHtml(str) {
  return String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;")
    .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** 临时禁用按钮，避免重复提交 */
async function withBusy(button, label, task) {
  const original = button ? button.textContent : null;
  if (button) {
    button.disabled = true;
    button.textContent = label || "处理中…";
  }
  try {
    return await task();
  } finally {
    if (button) {
      button.disabled = false;
      button.textContent = original;
    }
  }
}

function getExportMode() {
  const el = document.querySelector('input[name="exportMode"]:checked');
  return el ? el.value : "download";
}

function getImportMode() {
  const el = document.querySelector('input[name="importMode"]:checked');
  return el ? el.value : "upload";
}

// ============================================================
// ① 导出
// ============================================================

async function doExport(target, button) {
  const meta = EXPORT_TARGETS[target];
  if (!meta) return;

  const mode = getExportMode();
  const payload = { target: target, mode: mode };
  if (mode === "disk") {
    payload.dir = document.getElementById("exportDirPath").value || "";
    payload.filename = document.getElementById("exportFilename").value || "";
  }

  await withBusy(button, "导出中…", async () => {
    try {
      const data = await callApi(
        axiosClient.post("data-transfer/export", payload), "导出失败");

      if (mode === "download") {
        const filename = meta.prefix + "_" + timestamp() + "." + meta.ext;
        const mime = meta.ext === "sql"
          ? "application/sql;charset=utf-8"
          : "text/csv;charset=utf-8";
        downloadText(data, filename, mime);
        cocoMessage.success("导出成功：" + filename);
      } else {
        cocoMessage.success("已导出到: " + data);
      }
    } catch (e) {
      cocoMessage.error(e.message);
    }
  });
}

function toggleExportMode() {
  const browser = document.getElementById("exportDirBrowser");
  if (getExportMode() === "disk") {
    browser.style.display = "";
    if (!currentDir["export"]) {
      dirRefresh("export", "");
    }
  } else {
    browser.style.display = "none";
  }
}

// ============================================================
// ② 目录浏览
// ============================================================

async function dirRefresh(mode, path) {
  try {
    const data = await callApi(
      axiosClient.get("data-transfer/dirs", { params: { path: path || "" } }),
      "列目录失败");

    currentDir[mode] = data.path;
    const pathInput = document.getElementById(mode + "DirPath");
    if (pathInput) pathInput.value = data.path;

    const list = document.getElementById(mode + "DirList");
    list.innerHTML = "";

    const dirs = data.dirs || [];
    if (dirs.length === 0) {
      list.appendChild(el("div", "dir-empty", "（当前目录没有子文件夹）"));
    }
    dirs.forEach((name) => {
      const item = el("div", "dir-item", escapeHtml(name));
      item.addEventListener("click", () => {
        const sep = data.path.endsWith("/") ? "" : "/";
        dirRefresh(mode, data.path + sep + name);
      });
      list.appendChild(item);
    });

    // 导入模式额外列出当前目录的 .sql 文件
    if (mode === "import") {
      await dirRefreshFiles(data.path);
    }
  } catch (e) {
    cocoMessage.error(e.message);
  }
}

async function dirRefreshFiles(path) {
  try {
    const data = await callApi(
      axiosClient.get("data-transfer/files", { params: { path: path } }),
      "列文件失败");

    const list = document.getElementById("importDirList");
    const files = data.files || [];
    if (files.length > 0) {
      list.appendChild(el("div", "dir-files-title", "当前目录 .sql 文件"));
    }
    files.forEach((name) => {
      const item = el("div", "file-item", escapeHtml(name));
      item.addEventListener("click", () => {
        list.querySelectorAll(".file-item").forEach((n) => n.classList.remove("selected"));
        item.classList.add("selected");
        const sep = data.path.endsWith("/") ? "" : "/";
        selectedImportFile = data.path + sep + name;
        cocoMessage.success("已选择: " + name);
      });
      list.appendChild(item);
    });
  } catch (e) {
    cocoMessage.error(e.message);
  }
}

async function dirGoUp(mode) {
  try {
    const data = await callApi(
      axiosClient.get("data-transfer/dirs", { params: { path: currentDir[mode] || "" } }),
      "列目录失败");
    if (data.parent) {
      dirRefresh(mode, data.parent);
    } else {
      cocoMessage.warning("已到可浏览的最上层");
    }
  } catch (e) {
    cocoMessage.error(e.message);
  }
}

function dirGoPath(mode) {
  const path = document.getElementById(mode + "DirPath").value;
  dirRefresh(mode, path);
}

async function dirMkdir(button) {
  const name = document.getElementById("newDirName").value;
  if (!name) {
    cocoMessage.warning("请输入文件夹名");
    return;
  }
  await withBusy(button, "创建中…", async () => {
    try {
      const data = await callApi(
        axiosClient.post("data-transfer/mkdir", { path: currentDir["export"] || "", name: name }),
        "创建失败");
      cocoMessage.success("创建成功: " + data);
      document.getElementById("newDirName").value = "";
      dirRefresh("export", currentDir["export"]);
    } catch (e) {
      cocoMessage.error(e.message);
    }
  });
}

// ============================================================
// ③ 导入
// ============================================================

function toggleImportMode() {
  const disk = getImportMode() === "disk";
  document.getElementById("importUploadArea").style.display = disk ? "none" : "";
  document.getElementById("importDirBrowser").style.display = disk ? "" : "none";
  if (disk && !currentDir["import"]) {
    dirRefresh("import", "");
  }
}

async function importSql(button) {
  const mode = getImportMode();
  let payload;

  if (mode === "upload") {
    const fileInput = document.getElementById("importFileInput");
    if (!fileInput.files || fileInput.files.length === 0) {
      cocoMessage.warning("请选择 .sql 文件");
      return;
    }
    const file = fileInput.files[0];
    if (!file.name.toLowerCase().endsWith(".sql")) {
      cocoMessage.error("仅支持 .sql 文件");
      return;
    }
    payload = { mode: "upload", content: await readFileAsText(file) };
  } else {
    if (!selectedImportFile) {
      cocoMessage.warning("请先在目录中选择 .sql 文件");
      return;
    }
    payload = { mode: "disk", path: selectedImportFile };
  }

  payload.allowDrop = document.getElementById("allowDrop").checked;

  await withBusy(button, "导入中…", async () => {
    try {
      const message = await callApi(
        axiosClient.post("data-transfer/import", payload), "导入失败");
      cocoMessage.success(message || "导入成功");
    } catch (e) {
      await handleImportFailure(e, payload, button);
    }
  });
}

/**
 * 命中 DROP/TRUNCATE 拦截时，提示用户确认后带 allowDrop 重试。
 */
async function handleImportFailure(e, payload, button) {
  const message = e.message || "";
  const blocked = message.indexOf("DROP") !== -1 || message.indexOf("TRUNCATE") !== -1;

  if (!blocked) {
    cocoMessage.error(message);
    return;
  }

  const confirmed = window.confirm(
    message + "\n\n" +
    "继续执行会用脚本内容覆盖同名表，且无法撤销（系统已尝试保留导入前回滚备份）。\n" +
    "确定要继续导入吗？"
  );
  if (!confirmed) {
    cocoMessage.warning("已取消导入");
    return;
  }

  document.getElementById("allowDrop").checked = true;
  const retryPayload = Object.assign({}, payload, { allowDrop: true });
  await withBusy(button, "导入中…", async () => {
    try {
      const message2 = await callApi(
        axiosClient.post("data-transfer/import", retryPayload), "导入失败");
      cocoMessage.success(message2 || "导入成功");
    } catch (e2) {
      cocoMessage.error(e2.message);
    }
  });
}

function readFileAsText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (ev) => resolve(ev.target.result);
    reader.onerror = () => reject(new Error("读取文件失败"));
    reader.readAsText(file, "utf-8");
  });
}

// ============================================================
// ④ 日志文件
// ============================================================

async function loadLogList() {
  const menu = document.getElementById("logMenu");
  menu.innerHTML = "";
  try {
    const list = await callApi(axiosClient.get("data-transfer/logs"), "获取日志列表失败");
    if (!list || list.length === 0) {
      menu.appendChild(el("li", null, '<span class="dropdown-item-text dir-empty">（目录内没有文件）</span>'));
      return;
    }
    list.forEach((item) => {
      const li = el("li", null, "");
      const a = el("a", "dropdown-item", escapeHtml(item.name));
      a.href = "#";
      a.addEventListener("click", (ev) => {
        ev.preventDefault();
        downloadByPath(item.path);
        cocoMessage.success("开始下载: " + item.name);
      });
      li.appendChild(a);
      menu.appendChild(li);
    });
  } catch (e) {
    menu.appendChild(el("li", null, '<span class="dropdown-item-text dir-empty">加载失败</span>'));
    cocoMessage.error(e.message);
  }
}

// ============================================================
// 事件绑定
// ============================================================

/** 创建元素；text 已由调用方决定是否转义，html 用于极简场景 */
function el(tag, className, html) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (html !== undefined && html !== null) node.innerHTML = html;
  return node;
}

function bindEvents() {
  // 导出按钮
  document.querySelectorAll("[data-export]").forEach((btn) => {
    btn.addEventListener("click", () => doExport(btn.dataset.export, btn));
  });

  // 导出方式切换
  document.querySelectorAll('input[name="exportMode"]').forEach((r) => {
    r.addEventListener("change", toggleExportMode);
  });

  // 目录浏览按钮
  document.querySelectorAll("[data-dir-up]").forEach((btn) => {
    btn.addEventListener("click", () => dirGoUp(btn.dataset.dirUp));
  });
  document.querySelectorAll("[data-dir-go]").forEach((btn) => {
    btn.addEventListener("click", () => dirGoPath(btn.dataset.dirGo));
  });
  document.getElementById("mkdirBtn").addEventListener("click", (ev) => dirMkdir(ev.currentTarget));

  // 导入方式与执行
  document.querySelectorAll('input[name="importMode"]').forEach((r) => {
    r.addEventListener("change", toggleImportMode);
  });
  document.getElementById("importBtn").addEventListener("click", (ev) => importSql(ev.currentTarget));

  // 上传文件名显示
  document.getElementById("importFileInput").addEventListener("change", function () {
    const name = (this.files && this.files.length > 0) ? this.files[0].name : "";
    document.getElementById("importFileName").textContent = name ? "已选择: " + name : "未选择文件";
  });

  // 日志刷新
  document.getElementById("logRefreshBtn").addEventListener("click", loadLogList);
}

// ============================================================
// 初始化
// ============================================================

function init() {
  bindEvents();
  loadLogList();
  // 导入默认走上传模式，隐藏磁盘浏览器
  document.getElementById("importDirBrowser").style.display = "none";
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
