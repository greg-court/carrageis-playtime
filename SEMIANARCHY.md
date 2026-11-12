# SemiAnarchy adaptation

Based on upstream `mc26` at `b32a7af6e4ac0887514b4cdbc8eb3e21ae56f6a6`.
Keep the upstream release branch ancestry when merging updates.

`top.excluded-uuids` hides UUIDs only from `/tophours`. Visible ranks,
pagination and the viewer footer use the filtered list. Vanilla statistics,
direct playtime lookups and the underlying snapshot are preserved.
