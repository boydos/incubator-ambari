from __future__ import with_statement

import grp
import pwd
from resource_management.core import shell
from resource_management.core.providers import Provider


class UserProvider(Provider):
  def action_create(self):
    if not self.user:
      command = ['useradd', "-m"]
      self.log.info("Adding user %s" % self.resource)
    else:
      command = ['usermod']
      self.log.info("Modifying user %s" % (self.resource.username))

    options = dict(
      comment="-c",
      gid="-g",
      uid="-u",
      shell="-s",
      password="-p",
      home="-d",
    )

    if self.resource.system and not self.user:
      command.append("--system")

    if self.resource.groups:
      command += ["-G", ",".join(self.resource.groups)]

    for option_name, option_flag in options.items():
      option_value = getattr(self.resource, option_name)
      if option_flag and option_value:
        command += [option_flag, str(option_value)]

    command.append(self.resource.username)

    shell.checked_call(command)
    self.resource.updated()

  def action_remove(self):
    if self.user:
      command = ['userdel', self.resource.username]
      shell.checked_call(command)
      self.resource.updated()
      self.log.info("Removed user %s" % self.resource)

  @property
  def user(self):
    try:
      return pwd.getpwnam(self.resource.username)
    except KeyError:
      return None


class GroupProvider(Provider):
  def action_create(self):
    group = self.group
    if not group:
      command = ['groupadd']
      self.log.info("Adding group %s" % self.resource)
    else:
      command = ['groupmod']
      self.log.info("Modifying group %s" % (self.resource.group_name))
      
    options = dict(
        gid="-g",
        password="-p",
    )

    for option_name, option_flag in options.items():
      option_value = getattr(self.resource, option_name)
      if option_flag and option_value:
        command += [option_flag, str(option_value)]
        
    command.append(self.resource.group_name)

    shell.checked_call(command)
    self.resource.updated()

    group = self.group

  def action_remove(self):
    if self.group:
      command = ['groupdel', self.resource.group_name]
      shell.checked_call(command)
      self.resource.updated()
      self.log.info("Removed group %s" % self.resource)

  @property
  def group(self):
    try:
      return grp.getgrnam(self.resource.group_name)
    except KeyError:
      return None
