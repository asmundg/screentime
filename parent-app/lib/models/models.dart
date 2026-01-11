// Data models matching Firestore schema.

enum MemberRole { owner, admin, viewer }

enum Platform { windows, android, both }

enum RequestStatus { pending, approved, denied, processed }

class Family {
  final String id;
  final String name;
  final String ownerEmail;
  final Map<String, MemberRole> members;
  final DateTime createdAt;

  Family({
    required this.id,
    required this.name,
    required this.ownerEmail,
    required this.members,
    required this.createdAt,
  });

  factory Family.fromFirestore(String id, Map<String, dynamic> data) {
    final membersData = data['members'] as Map<String, dynamic>? ?? {};
    final members = membersData.map((email, role) => MapEntry(
          email,
          MemberRole.values.firstWhere(
            (r) => r.name == role,
            orElse: () => MemberRole.viewer,
          ),
        ));

    return Family(
      id: id,
      name: data['name'] ?? '',
      ownerEmail: data['ownerEmail'] ?? '',
      members: members,
      createdAt: (data['createdAt'] as dynamic)?.toDate() ?? DateTime.now(),
    );
  }

  Map<String, dynamic> toFirestore() => {
        'name': name,
        'ownerEmail': ownerEmail,
        'members': members.map((k, v) => MapEntry(k, v.name)),
        'createdAt': createdAt,
      };
}

class ChildUser {
  final String id;
  final String familyId;
  final String name;
  final int dailyLimitMinutes;
  final double todayUsedMinutes;
  final String lastResetDate;

  ChildUser({
    required this.id,
    required this.familyId,
    required this.name,
    required this.dailyLimitMinutes,
    required this.todayUsedMinutes,
    required this.lastResetDate,
  });

  double get remainingMinutes =>
      (dailyLimitMinutes - todayUsedMinutes).clamp(0, double.infinity);

  factory ChildUser.fromFirestore(String id, Map<String, dynamic> data) {
    return ChildUser(
      id: id,
      familyId: data['familyId'] ?? '',
      name: data['name'] ?? '',
      dailyLimitMinutes: data['dailyLimitMinutes'] ?? 120,
      todayUsedMinutes: (data['todayUsedMinutes'] ?? 0).toDouble(),
      lastResetDate: data['lastResetDate'] ?? '',
    );
  }

  Map<String, dynamic> toFirestore() => {
        'familyId': familyId,
        'name': name,
        'dailyLimitMinutes': dailyLimitMinutes,
        'todayUsedMinutes': todayUsedMinutes,
        'lastResetDate': lastResetDate,
      };
}

class Device {
  final String id;
  final String familyId;
  final String userId;
  final String name;
  final Platform platform;
  final DateTime lastSeen;
  final String? currentApp;
  final bool isLocked;

  Device({
    required this.id,
    required this.familyId,
    required this.userId,
    required this.name,
    required this.platform,
    required this.lastSeen,
    this.currentApp,
    this.isLocked = false,
  });

  bool get isOnline =>
      DateTime.now().difference(lastSeen).inMinutes < 2;

  factory Device.fromFirestore(String id, Map<String, dynamic> data) {
    return Device(
      id: id,
      familyId: data['familyId'] ?? '',
      userId: data['userId'] ?? '',
      name: data['name'] ?? '',
      platform: Platform.values.firstWhere(
        (p) => p.name == data['platform'],
        orElse: () => Platform.windows,
      ),
      lastSeen: (data['lastSeen'] as dynamic)?.toDate() ?? DateTime.now(),
      currentApp: data['currentApp'],
      isLocked: data['isLocked'] ?? false,
    );
  }
}

class ExtensionRequest {
  final String id;
  final String familyId;
  final String userId;
  final String deviceId;
  final String deviceName;
  final int requestedMinutes;
  final String? reason;
  final RequestStatus status;
  final DateTime createdAt;

  ExtensionRequest({
    required this.id,
    required this.familyId,
    required this.userId,
    required this.deviceId,
    required this.deviceName,
    required this.requestedMinutes,
    this.reason,
    required this.status,
    required this.createdAt,
  });

  factory ExtensionRequest.fromFirestore(String id, Map<String, dynamic> data) {
    return ExtensionRequest(
      id: id,
      familyId: data['familyId'] ?? '',
      userId: data['userId'] ?? '',
      deviceId: data['deviceId'] ?? '',
      deviceName: data['deviceName'] ?? '',
      requestedMinutes: data['requestedMinutes'] ?? 0,
      reason: data['reason'],
      status: RequestStatus.values.firstWhere(
        (s) => s.name == data['status'],
        orElse: () => RequestStatus.pending,
      ),
      createdAt: (data['createdAt'] as dynamic)?.toDate() ?? DateTime.now(),
    );
  }
}

class WhitelistItem {
  final String id;
  final String familyId;
  final Platform platform;
  final String identifier;
  final String displayName;
  final DateTime addedAt;

  WhitelistItem({
    required this.id,
    required this.familyId,
    required this.platform,
    required this.identifier,
    required this.displayName,
    required this.addedAt,
  });

  factory WhitelistItem.fromFirestore(String id, Map<String, dynamic> data) {
    return WhitelistItem(
      id: id,
      familyId: data['familyId'] ?? '',
      platform: Platform.values.firstWhere(
        (p) => p.name == data['platform'],
        orElse: () => Platform.both,
      ),
      identifier: data['identifier'] ?? '',
      displayName: data['displayName'] ?? '',
      addedAt: (data['addedAt'] as dynamic)?.toDate() ?? DateTime.now(),
    );
  }

  Map<String, dynamic> toFirestore() => {
        'familyId': familyId,
        'platform': platform.name,
        'identifier': identifier,
        'displayName': displayName,
        'addedAt': addedAt,
      };
}
