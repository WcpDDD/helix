# 核心表

Git issue 是协作黑板，Helix 是它的拓展。状态以主 issue 描述里的固定章节为准：改状态先写主 issue，写成功再改库；写失败或库和主 issue 不一致时，把库改成主 issue 上的状态。其余结构化字段仍以库为来源，库只覆盖这一段；章节以外的正文和全部评论留在 issue 上。打开详情时按编号向 GitLab 现读正文和评论，读到的文字不写入库。标签不存状态。

库通过 JPA 访问。本地运行和单元测试用 SQLite。MySQL 用 Testcontainers 做实机校验。下面按关系库来写，名字用蛇形。时间用带时区的时间戳，日期用日期。枚举用文本加检查约束，取值跟 [需求说明](requirements.md) 一致。

内部主键是库自己的整数。人看到的编号是 Git issue 号，单独一列，不拿它当主键。

## 同步

每张 issue 的描述里有一段固定章节：

```html
<!-- helix -->
…权威信息…
<!-- /helix -->
```

Helix 只替换这两个标记之间的文字。标记外面的正文保持原样，评论不动。标记还不存在时，把整段补在描述末尾，不改写已有正文。状态这一行以主 issue 里已经写下的为准。人改了主 issue 里的状态，定时治理线程会把库改成那个状态。列表只用库里已有的状态。

`description_revision` 在任一同步字段变化时加一。`synced_revision` 追上它，表示这一段已经写到 Git。

每张 issue，包括主 issue，这一段都写自己的字段：

- 状态、阶段、性质、负责人
- 允许变更范围、爆炸半径
- 结论、最近审阅结论、目标版本
- 关联的合并请求号，以及测试环境名称和访问地址

还有子 issue 时，主 issue 的这一段额外写任务汇总：汇总状态、三个时间、每张子 issue 的编号和状态，以及子 issue 之间的依赖（前置编号指向后继编号；需要契约时带上规格路径）。没有子 issue 时，主 issue 不写这份汇总，也没有依赖，任务状态就等于它自己的状态。

子 issue 的状态或范围变了，或者子 issue 之间的依赖变了，除了相关 issue 自己的 `description_revision`，主 issue 的世代也加一，因为汇总写在主 issue 的章节里。合并请求和测试环境只写在所属 issue 自己的章节里，不因此改主 issue 的世代。任务表上没有另一套同步世代。

契约正文不进这些表，也不进这一段。边上只留一条规格路径，指向仓库里的那份文件。承诺、场景和修订仍在文件里。

不写进这一段：正文、评论、契约的历史修订、红线逐条记录、运行的会话和工具输出。

## 表

这阶段的主表是 `task` 和 `issue`。合并请求、测试环境是 issue 的明细。子 issue 之间的依赖是单独的边。`project` 指定 workspace，`member` 挂在项目上。

### project

一个项目指定一个 GitLab 工程作为 workspace。可以有多个项目，一次只打开其中一个。看板上的 issue 就是当前项目这个工程里的 issue。openspec、步骤契约、行为场景、长期契约和知识库都放在这个工程里。

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| name | 显示名 |
| git_host | workspace 的 GitLab 主机，例如 hackers.oalite.com |
| git_owner | GitLab 命名空间 |
| git_repo | GitLab 工程名 |

`(git_host, git_owner, git_repo)` 唯一。合在一起就是这个项目的 workspace：`git_host` 上的 `git_owner/git_repo`。

### member

仓库里的人。负责人和锚定人都指到这里。

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| project_id | 所属项目 |
| display_name | 显示名 |
| git_login | Git 登录名 |

`(project_id, git_login)` 唯一。

### task

看板上的一条。它没有自己的编号、标题和 Git issue。编号、标题、负责人都读主 issue。这里只留排期，以及按子 issue 算出来的汇总。

`rollup_status` 跟着主 issue 的状态。除「已关闭」外，阶段由这个状态落入三个大阶段；已关闭保留关闭前的阶段。子 issue 不改写任务状态。

创建任务时必须同时插入主 issue。不允许存在没有主 issue 的任务。

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| project_id | 所属项目 |
| rollup_status | 看板上的状态，等于主 issue 的状态 |
| rollup_stage | 提案、实现、上线 |
| started_on | 开始日期 |
| expected_end_on | 预计结束 |
| finished_on | 实际结束，可空 |

### issue

一张 Git issue。每条任务恰好有一张 `is_main` 为真的行，其余是子 issue。允许变更范围、爆炸半径、合并请求和测试环境都在每一张 issue 上各自维护，不从别的 issue 推断，任务条目也不共用一份。

看板上的编号是主 issue 的 `git_issue_number`，不在任务上再存一份。看板上的标题是主 issue 的 `title`，负责人是主 issue 的 `owner_id`。

这一行只放这张 issue 自己的单值字段。合并请求和测试环境是列表，分别见下面两张明细。

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| project_id | 所属项目 |
| task_id | 所属任务 |
| is_main | 是否主 issue。同一任务里只能有一张为真 |
| git_issue_number | 真实 Git 编号。项目内唯一。主 issue 的这个号就是任务编号 |
| title | 标题。主 issue 的标题就是看板上的描述 |
| kind | BUG、技术变更、小型 feature、大型 feature |
| stage | 这张 issue 自己的阶段：提案、实现、上线 |
| status | 这张 issue 自己的状态，不是任务汇总 |
| author_id | 发起人 |
| owner_id | 承接人。主 issue 的承接人就是看板上的负责人 |
| allowed_scope | 允许变更范围 |
| blast_radius | 爆炸半径 |
| conclusion | 对人展示的结论 |
| review_summary | 最近一次审阅结论 |
| target_version | 目标版本，未排期为空 |
| version_confirmed_at | 确认进版本的时间，可空 |
| description_revision | 同步世代，默认 0 |
| synced_revision | 已写到 Git 的世代，默认 0 |
| synced_at | 上次写成功的时间，可空 |
| created_at | 创建时间 |
| updated_at | 更新时间 |

状态、范围、爆炸半径、结论、版本变化，以及这张 issue 的合并请求或测试环境变化，都要把这张 issue 的 `description_revision` 加一。子 issue 的状态或范围变化还要让主 issue 的世代加一。正文留在章节外面，不进这张表。

### issue_merge_request

明确关联到这张 issue 的一条合并请求。一条 issue 可以有多条。界面上的 `!号` 和「打开」都读这里，不从 issue 之间的关系里算。

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| issue_id | 所属 issue |
| iid | GitLab 合并请求号，界面写成 !86 |
| title | 标题 |
| state | 打开、已合并、已关闭 |
| web_url | 打开后进入的地址 |

`(issue_id, web_url)` 唯一。同一条合并请求可以同时挂在多张 issue 上，不同仓库里的合并请求号也会重复，所以不把 `iid` 做成唯一。

### issue_environment

这张 issue 的一个测试环境访问地址。一条 issue 可以有多个。它不挂在合并请求上，也不挂在任务上。

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| issue_id | 所属 issue |
| name | 显示名，例如固件预览 |
| url | 访问地址。界面上点这个地址打开 |

`(issue_id, url)` 唯一。

### issue_edge

子 issue 的归属和依赖写在 workspace 根目录的 `board.yaml`。看板读这份文件：`main` 是主 issue，`children` 是子 issue，`edges` 从前置指向后继。没有出现在任何 `children` 里的 issue 自己就是一条任务。需要契约时 `contract_ref` 是相对仓库根的路径。

下面这张表是同一份关系的库内副本。

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| task_id | 所属任务 |
| from_issue_id | 前置。必须是这个任务里的子 issue |
| to_issue_id | 后继。必须是这个任务里的另一张子 issue |
| needs_contract | 跨模块、接口、数据或协议为真。同一模块里的先后步骤为假 |
| contract_ref | 规格路径，相对仓库根。需要契约时必填，否则为空 |

`(from_issue_id, to_issue_id)` 唯一。两端不能相同，也不能是主 issue。

这条边记谁依赖谁，以及要看哪份规格。承诺、场景、修订和有没有锚定仍在那个文件里，不复制进这张表。`needs_contract` 为假时，`contract_ref` 为空，边只表示实现顺序。为真时，issue 里写了规格路径就填上；还没给出路径时留空。后继开工还要那份规格已经锚定。

## 这阶段不建的表

| 不建 | 放在哪 |
| --- | --- |
| 契约正文、修订、锚定记录 | 仓库里的规格文件。库不复制一份 |
| 评论 | Git issue 的评论区。打开详情时现读，不入库 |
| 运行记录 | 下一阶段再设计 |
| 产物、红线明细 | 跟着仓库和流程走，不在 issue 上 |
