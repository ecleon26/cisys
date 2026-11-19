\newpage

# Roles

[**cisys**](https://github.com/mnemosyne-systems/cisys) uses two sets of roles:

* **Keycloak realm roles** — `member`, `lead` and `admin`. They are assigned to the account and decide what you can do at the instance level, and are checked on every request.
* **Project member rows** — `MEMBER` and `LEAD` in the project's own `projectmember` table. They decide what a user can do inside a project. The project creator is automatically assigned `LEAD` when the project is created, and a project always keeps at least one `LEAD`.

The two are independent: a member with `LEAD` realm role is still a mere `MEMBER` of projects where they were assigned with the `MEMBER` role, and a `LEAD` row in a project does not grant a caller any global `lead` realm powers.

## Member

The **Member** role provides standard project access without project-management privileges. Members can view public projects, the private projects they are assigned to, and the build history and logs of the projects they can access. Members of a project (or users with a `LEAD` row in it) can start builds there; they cannot edit the project, manage its team, pin builds, or delete it.

## Lead

The **Lead** role is for people who run projects. Leads can create projects; the creator becomes the project's `LEAD` automatically. Every `LEAD` of a project can edit it, pin and unpin builds, start builds, and manage its team from the project's settings page (Team tab, `/<name>/settings?tab=team`): adding existing users to the project, assigning them `MEMBER` or `LEAD`, changing a member's role, and removing members.

A project always keeps at least one `LEAD`; removing or demoting the last one is rejected with `HTTP 409 Conflict`, even for an administrator. A project's creator is additionally protected: a project `LEAD`, including the creator themselves and any other project `LEAD`, cannot remove the creator or demote them out of `LEAD` while the creator remains `LEAD`. Only an administrator can remove or demote the creator; once that is done, the creator is managed like any other member and there is no automatic re-promotion.

## Admin

The **Admin** role manages the installation. Admins can see every project and the entire user directory, manage the team in any project, start, cancel and pin builds there, use credential references for private Git repositories, manage the build images, change the branding, and change the settings such as build retention, the build queue, the hosts build steps may reach, the checkout limits and the build resource limits. Only administrators can delete projects. A project keeps at least one `LEAD` even for an administrator, and the last one is protected: it cannot be removed or demoted (also `HTTP 409`).

## Access summary

The Member, Lead and Admin columns below refer to the caller's global Keycloak realm role; what a caller can do inside a project is decided by their `projectmember` row there (`MEMBER` or `LEAD`). A global `lead` has no automatic power inside a project whose `projectmember` rows were assigned differently.

| Action                              | Member | Lead | Admin |
| :---------------------------------- | :----- | :--- | :---- |
| View public projects                | yes    | yes  | yes   |
| View assigned private projects      | yes    | yes  | yes   |
| View other users' private projects  | no     | no   | yes   |
| Create a project                    | no     | yes  | yes   |
| View build history and logs         | yes    | yes  | yes   |
| Start a build                       | assigned (MEMBER) | assigned | yes   |
| Pin / unpin a build                 | no     | assigned | yes   |
| Comment on a build                  | assigned | assigned | yes   |
| Edit a comment                      | own    | own  | own   |
| Cancel a build                      | no     | own, or any in their project | yes |
| Manage project team                | no     | assigned | yes   |
| Delete a project                    | no     | no   | yes   |
| Use a Git credential reference      | no     | no   | yes   |
| Manage build images                 | no     | no   | yes   |
| Change branding                     | no     | no   | yes   |
| Change settings                     | no     | no   | yes   |

* "Assigned" means the caller has a row in the project's `projectmember` table; for Start a build any row (`MEMBER` or `LEAD`) suffices, while Pin / unpin, Cancel of others' builds and project management require `LEAD`.
* In the Cancel row, the global realm `lead` role additionally allows cancelling your own requested builds regardless of project rows.
* "own" means builds requested by the caller themselves (while holding the global `lead` realm role); "any in their project" means any build in a project where the caller is project `LEAD`, regardless of who requested it.
* The address of a project or build that does not exist or is not visible to the user opens the front page instead, so a private project is not revealed; the same applies to any unknown address.

The following chapters describe the application areas in more detail.
