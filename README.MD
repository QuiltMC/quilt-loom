# Quilt Loom
- add the Quilt Releases repository to projects automatically
- read the quilt.mod.json metadata of mods correctly, enabling JiJ, transitive access wideners, interface injection, etc to work properly
- Use Quiltflower by default via loom-quiltflower by Juuxel
- Automatically apply the Quilt Mappings on Loom plugin

## Contributing
### Setting up the project
Clone the project like normal **in a command line!**: `git clone git@github.com/quiltmc/quilt-loom quilt-loom-parent`.
Do **not** open the parent project (the root of this github repository) in your IDE. Instead,
run `./gradlew applyPatches` and import the newly created `patched-quilt-loom` subfolder into your IDE.

### Making a PR
This section is for people who are looking to add new features to Loom, fix a bug, etc. 

Commit your changes as normal **in the `patched-quilt-loom` subproject**. Please keep it to one commit per feature; use amend commits
(`git commit --amend` or the "Amend" checkbox in the Intellij IDE), a soft reset, or interactive rebase to change the patches (for example, to respond to feedback).

*Even if your PR changes code introduced in other patches, ONLY submit your changes as a NEW patch. A maintainer will manually merge your changes into the patches later.*

Once your changes are committed, `cd` to the parent project and run `./gradlew makePatches`. Once they're generated, commit the new patch as normal.

If you have questions about this process, the best way to reach us is to ask on our [Discord](https://discord.quiltmc.org/), but opening an issue works too!

## Maintaining
(TODO; tl;dr update the fabric-loom subproject, run gradlew applyPatches, fix conflicts as directed using git am --continue, run gradlew makePatches, profit)
