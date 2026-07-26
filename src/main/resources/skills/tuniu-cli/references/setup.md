# 途牛 CLI 环境准备与版本维护

> 本文档为 tuniu-cli 技能的**环境/安装/版本维护**参考，仅在首次使用或遇到 CLI 不可用/版本过低时按需加载。

## 运行环境要求

**运行环境必须安装 Node.js 18+ 与 tuniu-cli**，否则无法调用服务。

### 首次使用前自检

在第一次调用 `tuniu` 前，按顺序检查运行环境：

```bash
node --version
npm --version
tuniu --version
```

- 若 `node` 不存在，或版本低于 18：不要继续安装 `tuniu-cli`；告知用户需先安装或升级 Node.js 18+，否则 `npm install -g tuniu-cli@latest` 会失败。
- 若 `npm` 不存在：告知用户需安装 Node.js/npm 后再继续。
- 若 `tuniu` 不存在，但 Node.js 版本满足要求，自动执行 `npm install -g tuniu-cli@latest` 安装 CLI。
- 若 `tuniu` 已存在：检查版本是否满足 SKILL.md 头部的 `minCliVersion`。低于该版本时，先更新 CLI，再继续业务调用。

### 安装 tuniu-cli

```bash
# npm 全局安装（推荐）
npm install -g tuniu-cli@latest

# 或使用 npx 临时调用
npx tuniu-cli --version
```

## Skill 版本与更新说明

`tuniu-cli` 提供 **skill** 子命令，用于维护本助手在各 AI Agent 目录下的安装与版本查看，与业务调用（`tuniu call`）相互独立。

### CLI 与 Skill 兼容性

本 skill 依赖 `tuniu-cli` 版本不低于 SKILL.md 头部声明的 `minCliVersion`。使用时必须遵循：

1. 若 `tuniu --version` 低于 `minCliVersion`，先执行 `npm install -g tuniu-cli@latest` 更新 CLI。
2. 更新 CLI 后执行 `tuniu --version` 确认版本，再执行 `tuniu skill install` 更新本地 skill。
3. 若全局 npm 安装无权限，先尝试提示用户授权或使用当前环境可用的安装方式；不要继续调用低版本 CLI 中不存在的命令。
4. 若更新失败，明确告知用户当前 CLI 版本与 skill 不兼容，部分操作可能失效。

**使用场景简述**

- **`tuniu skill version`**：在已配置多台 Agent（如 Cursor、Claude 等）时，检查各目录下已安装的 skill 版本、来源与安装时间。
- **`tuniu skill install`**：需要**安装或更新**本 skill 时使用。默认仅写入 `~/.agents/skills/tuniu-cli/`；通过 `--agent` 可指定单个、多个（逗号分隔）或 `all`；`--dir` 可额外指定自定义 skills 根目录。
- **`npm install` / `npm ci`**：安装 `tuniu-cli` 时若启用脚本，**postinstall** 可能已自动复制内置 skill；若需与线上一致或显式更新，仍建议执行 `tuniu skill install`。

更完整的参数与示例见：`tuniu skill install --help`。
