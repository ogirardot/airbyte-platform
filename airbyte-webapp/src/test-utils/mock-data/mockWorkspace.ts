import { WorkspaceRead } from "core/api/types/AirbyteClient";

export const mockWorkspace: WorkspaceRead = {
  workspaceId: "47c74b9b-9b89-4af1-8331-4865af6c4e4d",
  customerId: "55dd55e2-33ac-44dc-8d65-5aa7c8624f72",
  organizationId: "db338860-f35c-4b5b-b57f-5171070bdcd9",
  email: "krishna@airbyte.com",
  name: "47c74b9b-9b89-4af1-8331-4865af6c4e4d",
  slug: "47c74b9b-9b89-4af1-8331-4865af6c4e4d",
  initialSetupComplete: true,
  displaySetupWizard: false,
  anonymousDataCollection: false,
  news: false,
  securityUpdates: false,
  notifications: [],
  notificationSettings: {
    sendOnFailure: {
      notificationType: ["customerio"],
    },
    sendOnSuccess: {
      notificationType: ["customerio"],
    },
    sendOnConnectionUpdate: {
      notificationType: [],
    },
    sendOnConnectionUpdateActionRequired: {
      notificationType: [],
    },
    sendOnSyncDisabled: {
      notificationType: [],
    },
    sendOnSyncDisabledWarning: {
      notificationType: [],
    },
    sendOnBreakingChangeWarning: {
      notificationType: [],
    },
    sendOnBreakingChangeSyncsDisabled: {
      notificationType: [],
    },
  },
};
