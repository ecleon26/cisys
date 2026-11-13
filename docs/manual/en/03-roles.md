\newpage

# Roles

[**cisys**](https://github.com/mnemosyne-systems/cisys) is designed around three roles, which are Keycloak realm roles. A signed-in user without one of them cannot use the application.

## Member

The **Member** role is the standard read-only role. Members can view public projects, and the build history and logs of the projects they can access.

## Lead

The **Lead** role is for people who run projects. Leads can do everything a member can, create projects, start manual builds on projects they can see, cancel the builds they started, and pin and cancel builds of the projects they created.

## Admin

The **Admin** role manages the installation. Admins can see every project including private projects of other users, start, pin and cancel builds, use credential references for private Git repositories, manage the build images, change the branding, and change the settings such as build retention, the build queue and the checkout limits.

## Access summary

| Action                              | Member | Lead | Admin |
| :---------------------------------- | :----- | :--- | :---- |
| View public projects                | yes    | yes  | yes   |
| View own private projects           | n/a    | yes  | yes   |
| View other users' private projects  | no     | no   | yes   |
| Create a project                    | no     | yes  | yes   |
| View build history and logs         | yes    | yes  | yes   |
| Start a manual build                | no     | yes  | yes   |
| Pin a build                         | no     | own  | yes   |
| Cancel a build                      | no     | own  | yes   |
| Use a Git credential reference      | no     | no   | yes   |
| Manage build images                 | no     | no   | yes   |
| Change branding                     | no     | no   | yes   |
| Change settings                     | no     | no   | yes   |

"View" always means projects the user may access. The address of a project or build that does not exist or is not visible to the user opens the front page instead, so a private project is not revealed; the same applies to any unknown address.

The following chapters describe the application areas in more detail.
