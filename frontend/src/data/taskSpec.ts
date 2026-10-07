export interface SpecFile {
  path: string
  markdown: string
}

export interface TaskSpecCheckout {
  workspace: string
  branch: string
  commit: string
  files: SpecFile[]
}

export function specFilesForTask(code: string): TaskSpecCheckout {
  return {
    workspace: 'qingflow-workspace',
    branch: 'feat/worksheet-list',
    commit: 'a1b2c3d',
    files: [
      {
        path: 'openspec/changes/worksheet-list/proposal.md',
        markdown: `## Why

任务 #${code} 里，工作表列表的查询和渲染绑在一次实现上。字段还没定时，界面只能等接口，或者提前写死还可能变的列。

## What Changes

- 查询和渲染拆成前后两步。查询先写清分页、筛选和错误。
- 渲染只使用查询已经写明的字段。空列表、加载和失败各自有界面。
- 查询契约改承诺时，渲染停止沿用旧字段。

## Capabilities

### New Capabilities

- \`worksheet-list-api\`: 工作表列表的查询接口。
- \`worksheet-list-ui\`: 工作表列表的加载、空状态、失败和翻页。

### Modified Capabilities

无。

## Impact

- 影响 pc-fe 的列表页和 qingflow-next 的列表查询。
- 不新增导出、排序字段或跨应用聚合。
`,
      },
      {
        path: 'openspec/changes/worksheet-list/design.md',
        markdown: `## Context

列表查询今天把分页、筛选和列一起返回。前端按返回的列渲染。列一变，两边要同时改。

## Goals / Non-Goals

**Goals:**

- 查询在资源和权限确认后返回 \`items\` 与 \`total\`。
- 非法筛选返回可识别的字段名。
- 渲染按查询文档里的列绘制，并保留上一页直到重试结束。

**Non-Goals:**

- 不在这次加入排序、导出或跨应用聚合。
- 不改列表以外的工作表读写。

## Decisions

1. 查询和渲染分两步。渲染的前置是查询这份规格，而不是查询的实现进度。
2. 没有数据时返回空 \`items\`，不省略字段。
3. 筛选不合法时返回 400，正文带字段名。界面标出该项，已显示的列表先留着。

## Risks / Trade-offs

- [渲染提前使用未写明的列] → 规格里写明不承诺的列，评审对照 spec 而不是对照当次响应。
- [查询字段变更后界面仍按旧列绘制] → 变更写进 spec 的 MODIFIED Requirements，渲染按新修订改。

## Migration Plan

无数据迁移。先合查询，再合渲染。回滚时两边一起回到上一修订。

## Open Questions

无。
`,
      },
      {
        path: 'openspec/changes/worksheet-list/tasks.md',
        markdown: `## 1. 查询规格

- [x] 1.1 写 worksheet-list-api 的分页、筛选错误和空列表要求。
- [ ] 1.2 按该 spec 实现查询接口。

## 2. 渲染规格

- [ ] 2.1 写 worksheet-list-ui 的加载、空状态、失败和翻页要求。
- [ ] 2.2 查询规格稳定后实现列表界面。

## 3. 验证

- [ ] 3.1 补查询和渲染的回归。
- [ ] 3.2 跑 OpenSpec 校验，并核对这次 change 的 spec delta。
`,
      },
      {
        path: 'openspec/changes/worksheet-list/specs/worksheet-list-api/spec.md',
        markdown: `## ADDED Requirements

### Requirement: Paged worksheet list
The list query MUST return \`items\` and \`total\`. \`page\` and \`pageSize\` MUST select the page. An empty result MUST return \`items\` as an empty array and MUST NOT omit the field.

#### Scenario: First page

- **WHEN** the client requests page 1 with a page size of 20 and the worksheet has rows
- **THEN** the response contains \`items\` for that page and the full \`total\`

#### Scenario: No rows

- **WHEN** the client requests a page and the worksheet has no rows
- **THEN** the response contains \`items: []\` and \`total: 0\`

### Requirement: Invalid filter
An unknown or illegal filter field MUST fail the request with HTTP 400. The error MUST name the field. The server MUST NOT apply a partial filter.

#### Scenario: Unknown filter field

- **WHEN** the client sends a filter field that the list query does not accept
- **THEN** the server responds 400 and the body names that field

## MODIFIED Requirements

无。
`,
      },
      {
        path: 'openspec/changes/worksheet-list/specs/worksheet-list-ui/spec.md',
        markdown: `## ADDED Requirements

### Requirement: List loading and failure
The list view MUST show that a request is in progress. On failure it MUST offer retry and MUST keep the previous page visible until the retry finishes.

#### Scenario: Request in progress

- **WHEN** a list request has started and has not finished
- **THEN** the view shows a loading state

#### Scenario: Request fails after a page is visible

- **WHEN** a later page request fails and a previous page is already shown
- **THEN** the previous page stays, and the view offers retry

### Requirement: Empty list
When \`items\` is empty the view MUST show an empty state and MUST NOT render a blank table.

#### Scenario: Empty items

- **WHEN** the query returns \`items: []\`
- **THEN** the view shows the empty state

## MODIFIED Requirements

无。
`,
      },
      {
        path: 'openspec/agreements/worksheet-list/list-query.md',
        markdown: `## 参与规格

- openspec/specs/worksheet-list-api/spec.md

## 前置

无。这一步可以先做。

## 承诺

- 分页参数为 \`page\`、\`pageSize\`。
- 响应包含 \`items\` 和 \`total\`。
- 非法筛选返回 400，并带字段名。

## 不承诺

- 排序字段。
- 导出。
- 跨应用聚合。
`,
      },
      {
        path: 'openspec/agreements/worksheet-list/list-render.md',
        markdown: `## 参与规格

- openspec/specs/worksheet-list-api/spec.md
- openspec/specs/worksheet-list-ui/spec.md

## 前置

- openspec/agreements/worksheet-list/list-query.md

## 承诺

- 只渲染 \`items\` 里已承诺的列。
- \`total\` 用来计算翻页。
- 400 时标出出错的筛选项。

## 不承诺

- 查询未承诺的列。
- 在查询契约变更未确认时继续改界面。
`,
      },
    ],
  }
}
