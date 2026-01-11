import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:cloud_functions/cloud_functions.dart';
import '../models/models.dart';

class FirestoreService {
  final FirebaseFirestore _db = FirebaseFirestore.instance;
  final FirebaseFunctions _functions = FirebaseFunctions.instance;

  // ============================================================
  // Families
  // ============================================================

  Stream<List<Family>> getFamiliesForUser(String email) {
    return _db
        .collection('families')
        .where(FieldPath(['members', email]), isNull: false)
        .snapshots()
        .map((snap) => snap.docs
            .map((doc) => Family.fromFirestore(doc.id, doc.data()))
            .toList());
  }

  Future<Family?> getFamily(String familyId) async {
    final doc = await _db.collection('families').doc(familyId).get();
    if (!doc.exists) return null;
    return Family.fromFirestore(doc.id, doc.data()!);
  }

  Future<String> createFamily(String name, String ownerEmail) async {
    final doc = await _db.collection('families').add({
      'name': name,
      'ownerEmail': ownerEmail,
      'members': {ownerEmail: 'owner'},
      'createdAt': FieldValue.serverTimestamp(),
      'timeTrackingMode': 'unified',
    });
    return doc.id;
  }

  Future<void> updateFamily(String familyId, Map<String, dynamic> data) {
    return _db.collection('families').doc(familyId).update(data);
  }

  // ============================================================
  // Children (Users)
  // ============================================================

  Stream<List<ChildUser>> getChildrenForFamily(String familyId) {
    return _db
        .collection('users')
        .where('familyId', isEqualTo: familyId)
        .snapshots()
        .map((snap) => snap.docs
            .map((doc) => ChildUser.fromFirestore(doc.id, doc.data()))
            .toList());
  }

  Future<String> createChild(String familyId, String name, int dailyLimitMinutes) async {
    final today = DateTime.now().toIso8601String().split('T')[0];
    final doc = await _db.collection('users').add({
      'familyId': familyId,
      'name': name,
      'dailyLimitMinutes': dailyLimitMinutes,
      'todayUsedMinutes': 0.0,
      'lastResetDate': today,
    });
    return doc.id;
  }

  Future<void> updateChild(String childId, Map<String, dynamic> data) {
    return _db.collection('users').doc(childId).update(data);
  }

  Future<void> deleteChild(String childId) {
    return _db.collection('users').doc(childId).delete();
  }

  // ============================================================
  // Devices
  // ============================================================

  Stream<List<Device>> getDevicesForFamily(String familyId) {
    return _db
        .collection('devices')
        .where('familyId', isEqualTo: familyId)
        .snapshots()
        .map((snap) => snap.docs
            .map((doc) => Device.fromFirestore(doc.id, doc.data()))
            .toList());
  }

  Future<void> deleteDevice(String deviceId) {
    return _db.collection('devices').doc(deviceId).delete();
  }

  // ============================================================
  // Extension Requests
  // ============================================================

  Stream<List<ExtensionRequest>> getPendingRequests(String familyId) {
    return _db
        .collection('extensionRequests')
        .where('familyId', isEqualTo: familyId)
        .where('status', isEqualTo: 'pending')
        .orderBy('createdAt', descending: true)
        .snapshots()
        .map((snap) => snap.docs
            .map((doc) => ExtensionRequest.fromFirestore(doc.id, doc.data()))
            .toList());
  }

  Future<void> approveRequest(String requestId) {
    return _db.collection('extensionRequests').doc(requestId).update({
      'status': 'approved',
      'respondedAt': FieldValue.serverTimestamp(),
    });
  }

  Future<void> denyRequest(String requestId) {
    return _db.collection('extensionRequests').doc(requestId).update({
      'status': 'denied',
      'respondedAt': FieldValue.serverTimestamp(),
    });
  }

  // ============================================================
  // Whitelist
  // ============================================================

  Stream<List<WhitelistItem>> getWhitelist(String familyId) {
    return _db
        .collection('whitelist')
        .where('familyId', isEqualTo: familyId)
        .snapshots()
        .map((snap) => snap.docs
            .map((doc) => WhitelistItem.fromFirestore(doc.id, doc.data()))
            .toList());
  }

  Future<String> addWhitelistItem(WhitelistItem item) async {
    final doc = await _db.collection('whitelist').add(item.toFirestore());
    return doc.id;
  }

  Future<void> removeWhitelistItem(String itemId) {
    return _db.collection('whitelist').doc(itemId).delete();
  }

  // ============================================================
  // Cloud Functions
  // ============================================================

  Future<String> generateRegistrationCode(String familyId, String userId) async {
    final result = await _functions
        .httpsCallable('generateRegistrationCode')
        .call({'familyId': familyId, 'userId': userId});
    return result.data['code'] as String;
  }

  Future<void> addFamilyMember(String familyId, String email, MemberRole role) async {
    await _functions.httpsCallable('addFamilyMember').call({
      'familyId': familyId,
      'email': email,
      'role': role.name,
    });
  }

  Future<void> removeFamilyMember(String familyId, String email) async {
    await _functions.httpsCallable('removeFamilyMember').call({
      'familyId': familyId,
      'email': email,
    });
  }

  Future<void> updateMemberRole(String familyId, String email, MemberRole role) async {
    await _functions.httpsCallable('updateMemberRole').call({
      'familyId': familyId,
      'email': email,
      'role': role.name,
    });
  }
}
