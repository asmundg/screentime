import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../models/models.dart';
import '../services/firestore_service.dart';

class FamilyScreen extends StatefulWidget {
  final Family family;

  const FamilyScreen({super.key, required this.family});

  @override
  State<FamilyScreen> createState() => _FamilyScreenState();
}

class _FamilyScreenState extends State<FamilyScreen> {
  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.family.name),
          bottom: const TabBar(
            tabs: [
              Tab(icon: Icon(Icons.child_care), text: 'Children'),
              Tab(icon: Icon(Icons.devices), text: 'Devices'),
              Tab(icon: Icon(Icons.check_circle), text: 'Whitelist'),
              Tab(icon: Icon(Icons.access_time), text: 'Requests'),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            _ChildrenTab(familyId: widget.family.id),
            _DevicesTab(familyId: widget.family.id),
            _WhitelistTab(familyId: widget.family.id),
            _RequestsTab(familyId: widget.family.id),
          ],
        ),
      ),
    );
  }
}

// =============================================================================
// Children Tab
// =============================================================================

class _ChildrenTab extends StatelessWidget {
  final String familyId;
  final _firestoreService = FirestoreService();

  _ChildrenTab({required this.familyId});

  Future<void> _addChild(BuildContext context) async {
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => _AddChildDialog(),
    );

    if (result != null) {
      await _firestoreService.createChild(
        familyId,
        result['name'],
        result['limit'],
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<List<ChildUser>>(
      stream: _firestoreService.getChildrenForFamily(familyId),
      builder: (context, snapshot) {
        final children = snapshot.data ?? [];

        return Scaffold(
          body: children.isEmpty
              ? const Center(child: Text('No children added'))
              : ListView.builder(
                  padding: const EdgeInsets.all(16),
                  itemCount: children.length,
                  itemBuilder: (context, index) {
                    final child = children[index];
                    final progress = child.todayUsedMinutes / child.dailyLimitMinutes;

                    return Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                CircleAvatar(child: Text(child.name[0])),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        child.name,
                                        style: const TextStyle(
                                          fontSize: 18,
                                          fontWeight: FontWeight.bold,
                                        ),
                                      ),
                                      Text(
                                        '${child.remainingMinutes.toInt()} min remaining',
                                        style: TextStyle(
                                          color: child.remainingMinutes <= 10
                                              ? Colors.red
                                              : Colors.grey,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                IconButton(
                                  icon: const Icon(Icons.add_circle),
                                  tooltip: 'Add device',
                                  onPressed: () => _showRegisterDevice(context, child),
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            LinearProgressIndicator(
                              value: progress.clamp(0, 1),
                              backgroundColor: Colors.grey[200],
                              color: progress > 0.9
                                  ? Colors.red
                                  : progress > 0.7
                                      ? Colors.orange
                                      : Colors.green,
                            ),
                            const SizedBox(height: 4),
                            Text(
                              '${child.todayUsedMinutes.toInt()} / ${child.dailyLimitMinutes} min used',
                              style: const TextStyle(fontSize: 12),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
          floatingActionButton: FloatingActionButton(
            onPressed: () => _addChild(context),
            child: const Icon(Icons.add),
          ),
        );
      },
    );
  }

  Future<void> _showRegisterDevice(BuildContext context, ChildUser child) async {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => _RegisterDeviceDialog(
        familyId: familyId,
        childId: child.id,
        childName: child.name,
      ),
    );
  }
}

class _AddChildDialog extends StatefulWidget {
  @override
  State<_AddChildDialog> createState() => _AddChildDialogState();
}

class _AddChildDialogState extends State<_AddChildDialog> {
  final _nameController = TextEditingController();
  int _limit = 120;

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add Child'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _nameController,
            decoration: const InputDecoration(labelText: 'Name'),
            textCapitalization: TextCapitalization.words,
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              const Text('Daily limit: '),
              Expanded(
                child: Slider(
                  value: _limit.toDouble(),
                  min: 15,
                  max: 480,
                  divisions: 31,
                  label: '$_limit min',
                  onChanged: (v) => setState(() => _limit = v.toInt()),
                ),
              ),
              Text('$_limit min'),
            ],
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () => Navigator.pop(context, {
            'name': _nameController.text,
            'limit': _limit,
          }),
          child: const Text('Add'),
        ),
      ],
    );
  }
}

class _RegisterDeviceDialog extends StatefulWidget {
  final String familyId;
  final String childId;
  final String childName;

  const _RegisterDeviceDialog({
    required this.familyId,
    required this.childId,
    required this.childName,
  });

  @override
  State<_RegisterDeviceDialog> createState() => _RegisterDeviceDialogState();
}

class _RegisterDeviceDialogState extends State<_RegisterDeviceDialog> {
  final _firestoreService = FirestoreService();
  String? _code;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _generateCode();
  }

  Future<void> _generateCode() async {
    try {
      final code = await _firestoreService.generateRegistrationCode(
        widget.familyId,
        widget.childId,
      );
      setState(() {
        _code = code;
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Register Device for ${widget.childName}'),
      content: _loading
          ? const SizedBox(
              height: 100,
              child: Center(child: CircularProgressIndicator()),
            )
          : _error != null
              ? Text('Error: $_error', style: const TextStyle(color: Colors.red))
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('Enter this code on the child\'s device:'),
                    const SizedBox(height: 16),
                    SelectableText(
                      _code!,
                      style: const TextStyle(
                        fontSize: 32,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 8,
                      ),
                    ),
                    const SizedBox(height: 16),
                    TextButton.icon(
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: _code!));
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Code copied')),
                        );
                      },
                      icon: const Icon(Icons.copy),
                      label: const Text('Copy code'),
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      'Code expires in 15 minutes',
                      style: TextStyle(color: Colors.grey, fontSize: 12),
                    ),
                  ],
                ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Done'),
        ),
      ],
    );
  }
}

// =============================================================================
// Devices Tab
// =============================================================================

class _DevicesTab extends StatelessWidget {
  final String familyId;
  final _firestoreService = FirestoreService();

  _DevicesTab({required this.familyId});

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<List<Device>>(
      stream: _firestoreService.getDevicesForFamily(familyId),
      builder: (context, snapshot) {
        final devices = snapshot.data ?? [];

        if (devices.isEmpty) {
          return const Center(child: Text('No devices registered'));
        }

        return ListView.builder(
          padding: const EdgeInsets.all(16),
          itemCount: devices.length,
          itemBuilder: (context, index) {
            final device = devices[index];

            return Card(
              child: ListTile(
                leading: Icon(
                  device.platform == Platform.windows
                      ? Icons.computer
                      : Icons.phone_android,
                  color: device.isOnline ? Colors.green : Colors.grey,
                ),
                title: Text(device.name),
                subtitle: Text(
                  device.isOnline
                      ? device.currentApp ?? 'Active'
                      : 'Last seen: ${_formatTime(device.lastSeen)}',
                ),
                trailing: IconButton(
                  icon: const Icon(Icons.delete),
                  onPressed: () => _confirmDelete(context, device),
                ),
              ),
            );
          },
        );
      },
    );
  }

  String _formatTime(DateTime time) {
    final diff = DateTime.now().difference(time);
    if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
    if (diff.inHours < 24) return '${diff.inHours}h ago';
    return '${diff.inDays}d ago';
  }

  Future<void> _confirmDelete(BuildContext context, Device device) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Remove Device'),
        content: Text('Remove "${device.name}"?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(context, true),
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
            child: const Text('Remove'),
          ),
        ],
      ),
    );

    if (confirm == true) {
      await _firestoreService.deleteDevice(device.id);
    }
  }
}

// =============================================================================
// Whitelist Tab
// =============================================================================

class _WhitelistTab extends StatelessWidget {
  final String familyId;
  final _firestoreService = FirestoreService();

  _WhitelistTab({required this.familyId});

  Future<void> _addItem(BuildContext context) async {
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => _AddWhitelistDialog(),
    );

    if (result != null) {
      final item = WhitelistItem(
        id: '',
        familyId: familyId,
        platform: result['platform'],
        identifier: result['identifier'],
        displayName: result['displayName'],
        addedAt: DateTime.now(),
      );
      await _firestoreService.addWhitelistItem(item);
    }
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<List<WhitelistItem>>(
      stream: _firestoreService.getWhitelist(familyId),
      builder: (context, snapshot) {
        final items = snapshot.data ?? [];

        return Scaffold(
          body: items.isEmpty
              ? const Center(child: Text('No whitelisted apps'))
              : ListView.builder(
                  padding: const EdgeInsets.all(16),
                  itemCount: items.length,
                  itemBuilder: (context, index) {
                    final item = items[index];

                    return Card(
                      child: ListTile(
                        leading: Icon(
                          item.platform == Platform.windows
                              ? Icons.computer
                              : item.platform == Platform.android
                                  ? Icons.phone_android
                                  : Icons.devices,
                        ),
                        title: Text(item.displayName),
                        subtitle: Text(item.identifier),
                        trailing: IconButton(
                          icon: const Icon(Icons.delete),
                          onPressed: () =>
                              _firestoreService.removeWhitelistItem(item.id),
                        ),
                      ),
                    );
                  },
                ),
          floatingActionButton: FloatingActionButton(
            onPressed: () => _addItem(context),
            child: const Icon(Icons.add),
          ),
        );
      },
    );
  }
}

class _AddWhitelistDialog extends StatefulWidget {
  @override
  State<_AddWhitelistDialog> createState() => _AddWhitelistDialogState();
}

class _AddWhitelistDialogState extends State<_AddWhitelistDialog> {
  final _nameController = TextEditingController();
  final _identifierController = TextEditingController();
  Platform _platform = Platform.both;

  @override
  void dispose() {
    _nameController.dispose();
    _identifierController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add to Whitelist'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _nameController,
            decoration: const InputDecoration(
              labelText: 'App Name',
              hintText: 'e.g., Khan Academy',
            ),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _identifierController,
            decoration: const InputDecoration(
              labelText: 'Identifier',
              hintText: 'e.g., khanacademy.exe or com.khan.app',
            ),
          ),
          const SizedBox(height: 16),
          InputDecorator(
            decoration: const InputDecoration(labelText: 'Platform'),
            child: DropdownButton<Platform>(
              value: _platform,
              isExpanded: true,
              underline: const SizedBox(),
              items: Platform.values
                  .map((p) => DropdownMenuItem(value: p, child: Text(p.name)))
                  .toList(),
              onChanged: (v) => setState(() => _platform = v!),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () => Navigator.pop(context, {
            'displayName': _nameController.text,
            'identifier': _identifierController.text,
            'platform': _platform,
          }),
          child: const Text('Add'),
        ),
      ],
    );
  }
}

// =============================================================================
// Requests Tab
// =============================================================================

class _RequestsTab extends StatelessWidget {
  final String familyId;
  final _firestoreService = FirestoreService();

  _RequestsTab({required this.familyId});

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<List<ExtensionRequest>>(
      stream: _firestoreService.getPendingRequests(familyId),
      builder: (context, snapshot) {
        final requests = snapshot.data ?? [];

        if (requests.isEmpty) {
          return const Center(child: Text('No pending requests'));
        }

        return ListView.builder(
          padding: const EdgeInsets.all(16),
          itemCount: requests.length,
          itemBuilder: (context, index) {
            final request = requests[index];

            return Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.access_time, color: Colors.orange),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            '+${request.requestedMinutes} minutes',
                            style: const TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text('From: ${request.deviceName}'),
                    if (request.reason != null) ...[
                      const SizedBox(height: 4),
                      Text('Reason: ${request.reason}'),
                    ],
                    const SizedBox(height: 16),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        TextButton(
                          onPressed: () =>
                              _firestoreService.denyRequest(request.id),
                          child: const Text('Deny'),
                        ),
                        const SizedBox(width: 8),
                        ElevatedButton(
                          onPressed: () =>
                              _firestoreService.approveRequest(request.id),
                          child: const Text('Approve'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}
