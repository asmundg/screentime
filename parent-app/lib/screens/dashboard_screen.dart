import 'package:flutter/material.dart';
import '../models/models.dart';
import '../services/auth_service.dart';
import '../services/firestore_service.dart';
import 'family_screen.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final _authService = AuthService();
  final _firestoreService = FirestoreService();

  Future<void> _createFamily() async {
    final name = await showDialog<String>(
      context: context,
      builder: (context) => _CreateFamilyDialog(),
    );

    if (name != null && name.isNotEmpty) {
      final email = _authService.currentUser?.email;
      if (email != null) {
        await _firestoreService.createFamily(name, email);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final email = _authService.currentUser?.email ?? '';

    return Scaffold(
      appBar: AppBar(
        title: const Text('Screen Time'),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            onPressed: () => _authService.signOut(),
            tooltip: 'Sign out',
          ),
        ],
      ),
      body: StreamBuilder<List<Family>>(
        stream: _firestoreService.getFamiliesForUser(email),
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }

          if (snapshot.hasError) {
            return Center(child: Text('Error: ${snapshot.error}'));
          }

          final families = snapshot.data ?? [];

          if (families.isEmpty) {
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.family_restroom, size: 64, color: Colors.grey),
                  const SizedBox(height: 16),
                  const Text('No families yet'),
                  const SizedBox(height: 8),
                  ElevatedButton.icon(
                    onPressed: _createFamily,
                    icon: const Icon(Icons.add),
                    label: const Text('Create Family'),
                  ),
                ],
              ),
            );
          }

          return ListView.builder(
            padding: const EdgeInsets.all(16),
            itemCount: families.length,
            itemBuilder: (context, index) {
              final family = families[index];
              final role = family.members[email] ?? MemberRole.viewer;

              return Card(
                child: ListTile(
                  leading: const CircleAvatar(
                    child: Icon(Icons.family_restroom),
                  ),
                  title: Text(family.name),
                  subtitle: Text('Role: ${role.name}'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => FamilyScreen(family: family),
                    ),
                  ),
                ),
              );
            },
          );
        },
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _createFamily,
        child: const Icon(Icons.add),
      ),
    );
  }
}

class _CreateFamilyDialog extends StatefulWidget {
  @override
  State<_CreateFamilyDialog> createState() => _CreateFamilyDialogState();
}

class _CreateFamilyDialogState extends State<_CreateFamilyDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Create Family'),
      content: TextField(
        controller: _controller,
        decoration: const InputDecoration(
          labelText: 'Family Name',
          hintText: 'e.g., Smith Family',
        ),
        autofocus: true,
        textCapitalization: TextCapitalization.words,
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () => Navigator.pop(context, _controller.text),
          child: const Text('Create'),
        ),
      ],
    );
  }
}
