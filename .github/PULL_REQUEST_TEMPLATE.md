## 改动说明

简要描述这个 PR 做了什么、为什么这么做。

## 关联 Issue

Fixes #（issue 编号，如有）

## 自检清单

- [ ] `./gradlew assembleDebug` 本地构建通过
- [ ] `./gradlew testDebugUnitTest` 单元测试通过
- [ ] 涉及 UI 的改动已在真机或模拟器上验证
- [ ] 没有引入与本需求无关的重构或元数据改动
- [ ] 遵守仓库现有约束：不引入 Hilt / Room，网络层仍用 OkHttp + kotlinx.serialization
